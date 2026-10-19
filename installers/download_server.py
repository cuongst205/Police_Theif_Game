"""Read the official ZIP directory via HTTP ranges; omit debug symbols."""
import io
import subprocess
import zipfile
from pathlib import Path

URL = "https://fastdl.mongodb.org/windows/mongodb-windows-x86_64-9.0.2.zip"

class RemoteZip(io.RawIOBase):
    def __init__(self):
        header = subprocess.check_output(["curl.exe", "-sI", "--max-time", "30", URL]).decode()
        self.length = int(next(line.split(":", 1)[1].strip() for line in header.splitlines() if line.lower().startswith("content-length:")))
        self.position = 0

    def seekable(self):
        return True

    def tell(self):
        return self.position

    def seek(self, offset, whence=0):
        self.position = offset if whence == 0 else self.position + offset if whence == 1 else self.length + offset
        return self.position

    def read(self, size=-1):
        if size < 0:
            size = self.length - self.position
        size = min(size, self.length - self.position)
        if size <= 0:
            return b""
        data = subprocess.check_output(["curl.exe", "-sf", "--max-time", "120", "--range", f"{self.position}-{self.position + size - 1}", URL])
        if len(data) != size:
            raise RuntimeError("Unexpected byte range size")
        self.position += len(data)
        return data

if __name__ == "__main__":
    with zipfile.ZipFile(RemoteZip()) as archive:
        for entry in archive.infolist():
            if entry.is_dir() or entry.filename.endswith((".pdb", "mongos.exe", "Install-Compass.ps1")):
                continue
            destination = Path("mongodb") / Path(entry.filename).relative_to("mongodb-win32-x86_64-windows-9.0.2")
            destination.parent.mkdir(parents=True, exist_ok=True)
            print("Downloading", entry.filename, entry.compress_size, flush=True)
            destination.write_bytes(archive.read(entry))
            print("Saved", destination, flush=True)
