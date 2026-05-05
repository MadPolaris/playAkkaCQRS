import subprocess

with open('app/views/showcase.scala.html.bak') as f:
    original_lines = f.readlines()

start_idx = 0
end_idx = 0
for i, line in enumerate(original_lines):
    if "id: 'node-aggregate'" in line: start_idx = i - 1
    if "id: 'node-journal'" in line: end_idx = i - 1

for i in range(start_idx, end_idx):
    lines = original_lines[:i] + original_lines[i+1:]
    with open('app/views/showcase.scala.html', 'w') as f:
        f.writelines(lines)
    res = subprocess.run(['sbt', 'compile'], capture_output=True, text=True)
    if 'Twirl compilation failed' not in res.stdout:
        print(f"Success by deleting line {i+1}: {original_lines[i].strip()}")
        break
else:
    print("No single line deletion fixed it.")