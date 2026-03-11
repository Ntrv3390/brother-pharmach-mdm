import os
import shutil

root_dir = '/home/mohammed/hmdm/hmdm-android'

text_extensions = {'.java', '.kt', '.xml', '.gradle', '.pro', '.md', '.properties', '.sh'}
exclude_dirs = {'.git', '.gradle', 'build', 'captures', '.idea'}

def replace_in_file(filepath):
    try:
        with open(filepath, 'r', encoding='utf-8') as f:
            content = f.read()
            
        new_content = content.replace('com.hmdm', 'com.brother.pharmach.mdm')
        new_content = new_content.replace('Headwind MDM', 'Brother Pharmach MDM')
        
        if new_content != content:
            with open(filepath, 'w', encoding='utf-8') as f:
                f.write(new_content)
            print(f'Updated {filepath}')
    except Exception as e:
        print(f"Error reading {filepath}: {e}")

for dirpath, dirnames, filenames in os.walk(root_dir):
    dirnames[:] = [d for d in dirnames if d not in exclude_dirs]
    for filename in filenames:
        ext = os.path.splitext(filename)[1]
        if ext in text_extensions or filename in ['build.gradle', 'settings.gradle', 'proguard-rules.pro', 'AndroidManifest.xml']:
            replace_in_file(os.path.join(dirpath, filename))

# Find the directories named 'hmdm' inside a 'com' directory
dirs_to_move = []
for dirpath, dirnames, filenames in os.walk(root_dir, topdown=False):
    if os.path.basename(dirpath) == 'hmdm' and os.path.basename(os.path.dirname(dirpath)) == 'com':
        dirs_to_move.append(dirpath)

for d in dirs_to_move:
    parent = os.path.dirname(d) # This is '.../src/main/java/com'
    brother_dir = os.path.join(parent, 'brother')
    pharmach_dir = os.path.join(brother_dir, 'pharmach')
    mdm_dir = os.path.join(pharmach_dir, 'mdm')
    
    os.makedirs(mdm_dir, exist_ok=True)
    
    # Move all contents of 'hmdm' to 'mdm'
    for item in os.listdir(d):
        src = os.path.join(d, item)
        dst = os.path.join(mdm_dir, item)
        shutil.move(src, dst)
    
    # Remove the empty hmdm directory
    os.rmdir(d)
    print(f'Moved contents of {d} to {mdm_dir} and deleted {d}')

