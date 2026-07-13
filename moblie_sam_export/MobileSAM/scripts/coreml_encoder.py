import torch
import coremltools as ct
from mobile_sam import sam_model_registry

sam = sam_model_registry["vit_t"](checkpoint="./weights/mobile_sam.pt")
sam.eval()

class Encoder(torch.nn.Module):
    def __init__(self, sam):
        super().__init__()
        self.image_encoder = sam.image_encoder
    def forward(self, x):
        return self.image_encoder(x)

encoder = Encoder(sam).eval()
example = torch.randn(1, 3, 1024, 1024)

traced = torch.jit.trace(encoder, example)

mlmodel = ct.convert(
    traced,
    inputs=[ct.TensorType(name="image", shape=(1, 3, 1024, 1024))],
    convert_to="neuralnetwork",
    compute_units=ct.ComputeUnit.ALL,
)
mlmodel.save("./weights/MobileSAM_Encoder.mlmodel")

print("Encoder saved.")
