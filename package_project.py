import os
import zipfile
import sys

def package_project():
    root_dir = os.path.dirname(os.path.abspath(__file__))
    zip_filename = os.path.join(root_dir, "SmartTVMediaPlayer_Portable.zip")
    
    # If zip already exists, delete it first to avoid nesting/duplication
    if os.path.exists(zip_filename):
        try:
            os.remove(zip_filename)
        except Exception as e:
            print(f"Warning: Could not remove old zip: {e}")
            
    print(f"Packaging project from: {root_dir}")
    print(f"Output ZIP will be: {zip_filename}")
    
    exclude_dirs = {
        '.git',
        '.gradle',
        'build',
        '__pycache__',
    }
    
    exclude_files = {
        'SmartTVMediaPlayer_Portable.zip',
        '.gitattributes',
        '.gitignore',
    }
    
    count_files = 0
    
    with zipfile.ZipFile(zip_filename, 'w', zipfile.ZIP_DEFLATED) as zipf:
        for root, dirs, files in os.walk(root_dir):
            # Modify dirs in-place to skip excluded directories
            for d in list(dirs):
                if d in exclude_dirs or d.startswith('.'):
                    dirs.remove(d)
            
            # Additional double check to avoid any nested build folders
            if any(part in root.split(os.sep) for part in exclude_dirs):
                continue
                
            for file in files:
                if file in exclude_files or file.endswith('.zip') or file.startswith('.'):
                    continue
                    
                file_path = os.path.join(root, file)
                # Compute relative path for zip entry
                arcname = os.path.relpath(file_path, root_dir)
                
                # Print every 50 files to avoid flooding the console while showing progress
                if count_files % 50 == 0:
                    print(f"Adding file #{count_files}: {arcname}")
                
                zipf.write(file_path, arcname)
                count_files += 1

    print(f"\n[OK] Project packaged successfully!")
    print(f"Total files added: {count_files}")
    print(f"Archive file: {zip_filename}")

if __name__ == "__main__":
    package_project()
