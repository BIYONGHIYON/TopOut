import torch
import coremltools as ct
from mobile_sam import sam_model_registry

# 1. 모델 로드
sam = sam_model_registry["vit_t"](checkpoint="./weights/mobile_sam.pt")
sam.eval()

# 🚀 핵심 수정 1: 불리언 인덱싱 우회 패치 (CoreML Reshape 에러 완벽 해결 및 오타 수정)
def patched_embed_points(self, points, labels, pad):
    points = points + 0.5
    if pad:
        padding_point = torch.zeros((points.shape[0], 1, 2), device=points.device)
        padding_label = -torch.ones((labels.shape[0], 1), device=labels.device)
        points = torch.cat([points, padding_point], dim=1)
        labels = torch.cat([labels, padding_label], dim=1)
        
    point_embedding = self.pe_layer.forward_with_coords(points, self.input_image_size)
    
    # 불리언 마스킹(labels == x)을 트레이싱에 안전한 산술 연산으로 대체합니다.
    labels_eq_minus1 = (labels == -1).unsqueeze(-1).float()
    labels_eq_0 = (labels == 0).unsqueeze(-1).float()
    labels_eq_1 = (labels == 1).unsqueeze(-1).float()
    
    point_embedding = point_embedding * (1.0 - labels_eq_minus1)
    point_embedding = point_embedding + labels_eq_minus1 * self.not_a_point_embed.weight.view(1, 1, -1)
    
    # 👇 여기서 오타 수정됨 (point_embed -> point_embeddings)
    point_embedding = point_embedding + labels_eq_0 * self.point_embeddings[0].weight.view(1, 1, -1)
    point_embedding = point_embedding + labels_eq_1 * self.point_embeddings[1].weight.view(1, 1, -1)
    
    return point_embedding

# PromptEncoder의 내부 메서드를 패치한 함수로 교체 (몽키 패치)
sam.prompt_encoder._embed_points = patched_embed_points.__get__(sam.prompt_encoder)

embed_dim = sam.prompt_encoder.embed_dim
embed_size = sam.prompt_encoder.image_embedding_size
mask_input_size = [4 * x for x in embed_size]
NUM_POINTS = 2

class DecoderWrapper(torch.nn.Module):
    def __init__(self, sam):
        super().__init__()
        self.pe = sam.prompt_encoder
        self.decoder = sam.mask_decoder

    def forward(self, image_embeddings, point_coords, point_labels, mask_input, has_mask_input):
        
        # 🚀 핵심 수정 2: has_mask_input 로직 정규화
        # 이전에 작성하신 코드처럼 mask_input에 0을 곱해서 pe에 통과시키면, 
        # CNN 편향(Bias) 때문에 완벽한 0이 나오지 않아 첫 터치 시 마스크 품질이 크게 떨어집니다.
        # 아래처럼 SAM의 기본 동작인 'no_mask_embed'를 명시적으로 스위칭하도록 구성해야 합니다.
        
        sparse, _ = self.pe(
            points=(point_coords, point_labels),
            boxes=None,
            masks=None,
        )
        
        dense_mask = self.pe.mask_downscaling(mask_input)
        no_mask = self.pe.no_mask_embed.weight.reshape(1, -1, 1, 1).expand_as(dense_mask)
        
        has_mask_input_reshaped = has_mask_input.view(-1, 1, 1, 1)
        # has_mask_input이 1이면 dense_mask, 0이면 no_mask를 사용
        dense = dense_mask * has_mask_input_reshaped + no_mask * (1.0 - has_mask_input_reshaped)
        
        low_res_masks, iou_pred = self.decoder(
            image_embeddings=image_embeddings,
            image_pe=self.pe.get_dense_pe(),
            sparse_prompt_embeddings=sparse,
            dense_prompt_embeddings=dense,
            multimask_output=True,
        )
        return low_res_masks, iou_pred

wrapper = DecoderWrapper(sam).eval()

dummy = (
    torch.randn(1, embed_dim, *embed_size, dtype=torch.float),
    torch.randint(0, 1024, (1, NUM_POINTS, 2), dtype=torch.float),
    torch.randint(0, 2, (1, NUM_POINTS), dtype=torch.float),
    torch.zeros(1, 1, *mask_input_size, dtype=torch.float),
    torch.tensor([0], dtype=torch.float), 
)

traced = torch.jit.trace(wrapper, dummy, check_trace=False)

mlmodel = ct.convert(
    traced,
    inputs=[
        ct.TensorType(name="image_embeddings", shape=dummy[0].shape),
        ct.TensorType(name="point_coords", shape=dummy[1].shape),
        ct.TensorType(name="point_labels", shape=dummy[2].shape),
        ct.TensorType(name="mask_input", shape=dummy[3].shape),
        ct.TensorType(name="has_mask_input", shape=dummy[4].shape),
    ],
    outputs=[
        ct.TensorType(name="low_res_masks"),
        ct.TensorType(name="iou_predictions"),
    ],
    convert_to="neuralnetwork",
    compute_units=ct.ComputeUnit.CPU_ONLY,
)
mlmodel.save("./weights/MobileSAM_Decoder_Fixed.mlmodel")
print("✅ Fixed Decoder saved.")