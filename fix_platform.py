import os
import re

def process_file(filepath):
    with open(filepath, 'r') as f:
        content = f.read()

    original = content
    
    # Replace Platform boolean getters
    content = re.sub(r'Platform\.isAndroid\b', 'true', content)
    content = re.sub(r'Platform\.isWindows\b', 'false', content)
    content = re.sub(r'Platform\.isLinux\b', 'false', content)
    content = re.sub(r'Platform\.isMacOS\b', 'false', content)
    content = re.sub(r'Platform\.isIOS\b', 'false', content)
    content = re.sub(r'Platform\.isFuchsia\b', 'false', content)
    content = re.sub(r'Platform\.operatingSystem\b', '"android"', content)
    
    # We DO NOT replace kIsWeb or kIsDesktop to avoid breaking declarations.
    
    if content != original:
        with open(filepath, 'w') as f:
            f.write(content)

for root, _, files in os.walk('lib'):
    for file in files:
        if file.endswith('.dart'):
            process_file(os.path.join(root, file))
