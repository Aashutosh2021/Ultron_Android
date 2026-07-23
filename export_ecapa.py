import sys
import torch
import torch.nn as nn

sys.stdout.reconfigure(encoding='utf-8')

class EcapaTdnnModel(nn.Module):
    def __init__(self, emb_dim=192):
        super().__init__()
        self.conv = nn.Conv1d(1, 64, kernel_size=5, stride=2, padding=2)
        self.relu = nn.ReLU()
        self.fc = nn.Linear(64, emb_dim)
        
    def forward(self, x):
        if x.dim() == 2:
            x = x.unsqueeze(1)
        out = self.relu(self.conv(x))
        out = out.mean(dim=2)
        out = self.fc(out)
        return out / torch.norm(out, p=2, dim=-1, keepdim=True)

if __name__ == "__main__":
    model = EcapaTdnnModel(192)
    model.eval()
    dummy = torch.randn(1, 16000)
    torch.onnx.export(
        model,
        dummy,
        'app/src/main/assets/ecapa_tdnn.onnx',
        input_names=['input'],
        output_names=['embedding'],
        dynamo=False
    )
    print('Exported ecapa_tdnn.onnx successfully')
