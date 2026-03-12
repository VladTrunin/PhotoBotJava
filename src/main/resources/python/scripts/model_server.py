import torch
from transformers import AutoProcessor, Qwen2_5_VLForConditionalGeneration, BitsAndBytesConfig
from PIL import Image
import socket
import json
import sys

MODEL_PATH = sys.argv[1]
PROMPT_TEXT = sys.argv[2]

sys.stdout.reconfigure(encoding='utf-8')
print("Загрузка модели...")

processor = AutoProcessor.from_pretrained(MODEL_PATH)

bnb_config = BitsAndBytesConfig(
    load_in_8bit=True,
    llm_int8_enable_fp32_cpu_offload=True
)

model = Qwen2_5_VLForConditionalGeneration.from_pretrained(
    MODEL_PATH,
    quantization_config=bnb_config,
    device_map="auto",
    offload_folder="I:/AI/QWEN/offload_temp",
    local_files_only=True
)

model.eval()

print("Модель загружена")

def classify(image_path, prompt):

    image = Image.open(image_path).convert("RGB")

    messages = [{
        "role": "user",
        "content": [
            {"type": "image", "image": image},
            {"type": "text", "text": prompt}
        ]
    }]

    text = processor.apply_chat_template(
        messages,
        tokenize=False,
        add_generation_prompt=True
    )

    inputs = processor(
        text=[text],
        images=[image],
        return_tensors="pt"
    ).to(model.device)

    with torch.no_grad():
        output_ids = model.generate(
            **inputs,
            max_new_tokens=30,
            do_sample=False
        )

    generated_ids = output_ids[:, inputs.input_ids.shape[1]:]

    answer = processor.batch_decode(
        generated_ids,
        skip_special_tokens=True
    )[0].strip()

    return answer


server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
server.bind(("127.0.0.1", 5000))
server.listen()

print("AI сервер готов")

while True:

    conn, addr = server.accept()

    data = conn.recv(4096).decode()

    try:
        request = json.loads(data)
    except:
        conn.close()
        continue

    # проверка готовности сервера
    if request.get("cmd") == "ping":
        conn.send("READY".encode())
        conn.close()
        continue

    photo = request["photo"]

    result = classify(photo, PROMPT_TEXT)

    conn.send(result.encode())

    conn.close()