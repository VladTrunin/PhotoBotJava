import socket
import json
import sys

sys.stdout.reconfigure(encoding='utf-8')

photo = sys.argv[1]

data = {
    "photo": photo
}

client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
client.connect(("localhost", 5000))

client.send(json.dumps(data).encode())

result = client.recv(4096).decode()

print(result)

client.close()