import warnings
import torch
from mobile_sam import sam_model_registry

sam = sam_model_registry["vit_t"](checkpoint="./weights/mobile_sam.pt")
sam.eval()

class Encoder(torch.nn.Module):
    def __init__(self, sam):
        super().__init__()
        self.image_encoder = sam.image_encoder
    def forward(self, x):
        return self.image_encoder(x)

encoder = Encoder(sam)
dummy = torch.randn(1, 3, 1024, 1024, dtype=torch.float)

with warnings.catch_warnings():
    warnings.simplefilter("ignore")
    torch.onnx.export(
        encoder,
        dummy,
        "./weights/mobile_sam_encoder.onnx",
        export_params=True,
        opset_version=17,
        do_constant_folding=True,
        input_names=["images"],
        output_names=["image_embeddings"],
        dynamo=False,
    )

import onnxruntime as ort
import numpy as np
sess = ort.InferenceSession("./weights/mobile_sam_encoder.onnx", providers=["CPUExecutionProvider"])
out = sess.run(None, {"images": dummy.numpy()})
print("Encoder OK, output shape:", out[0].shape)
