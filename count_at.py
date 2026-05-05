import sys

with open('app/views/showcase.scala.html.bak') as f:
    lines = f.readlines()

script_start = 0
for i, l in enumerate(lines):
    if '<script>' in l:
        script_start = i
        break

text = "".join(lines[script_start:])

# We want to see if there is any unclosed quote that might cause Twirl to think braces are inside a string.
# Wait, Twirl doesn't parse JS strings properly! Twirl parser parses Scala strings.
# Scala strings can be "", """", or s"". But in Twirl, if it's not inside an @ block, it just sees HTML.
# But wait, Twirl actually *doesn't* parse Javascript strings at all. It just looks for @.
# But wait! What if there's an @ inside a Javascript string that we added?
# Let's check for ANY @ in the text!
count = 0
for i, line in enumerate(lines[script_start:]):
    if '@' in line:
        print(f"Line {script_start + i + 1}: {line.strip()}")
        count += 1
print(f"Total @ found: {count}")
