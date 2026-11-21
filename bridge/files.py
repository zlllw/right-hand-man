"""rhmdir 文件工作区（契约 B 的 B3 /files 接口 + B8 安全要求）。

* 上传只落盘到工作区根目录（真机上是 ``$HOME/rhmdir``），用户与 Agent 共用。
* 路径一律规范化后校验：拒绝 ``..``、绝对路径、符号链接逃逸。
* 超限返回明确错误（413），**不做截断**（B8 第 3 条）。
* 重名不覆盖：自动改名为 ``name (2).ext``（与手机原型一致）。

只用标准库：Python 3.13+ 已移除 ``cgi``，multipart 用 ``email`` 解析。
"""

import email
import mimetypes
import os
import re

MAX_UPLOAD_BYTES = 20 * 1024 * 1024  # 20MB，与手机原型的限制一致

_CONTROL_CHARS = re.compile(r"[\x00-\x1f]")


class FileTooLarge(Exception):
    pass


class UnsafePath(Exception):
    pass


class BadUpload(Exception):
    pass


def sanitize_filename(raw):
    """从 multipart 的 filename 里取出安全的单段文件名。"""
    if not raw:
        raise BadUpload("missing_filename")
    name = raw.replace("\\", "/").rsplit("/", 1)[-1]
    name = _CONTROL_CHARS.sub("", name).strip()
    if name in ("", ".", "..") or name.startswith("."):
        raise BadUpload("invalid_filename")
    if len(name) > 200:
        name = name[-200:]
    return name


def unique_name(directory, filename):
    """重名不覆盖：``report.pdf`` → ``report (2).pdf`` → ``report (3).pdf``。"""
    base, ext = os.path.splitext(filename)
    candidate = filename
    counter = 2
    while os.path.exists(os.path.join(directory, candidate)):
        candidate = "{0} ({1}){2}".format(base, counter, ext)
        counter += 1
    return candidate


class Workspace(object):
    """``$HOME/rhmdir`` 的文件读写面。"""

    def __init__(self, root, max_bytes=MAX_UPLOAD_BYTES):
        self.max_bytes = max_bytes
        self.root = os.path.realpath(root)
        os.makedirs(self.root, exist_ok=True)

    def display_root(self):
        home = os.path.expanduser("~")
        if self.root.startswith(home + os.sep):
            return "~" + self.root[len(home):]
        return self.root

    def save_upload(self, filename, data):
        name = unique_name(self.root, sanitize_filename(filename))
        path = os.path.join(self.root, name)
        tmp = path + ".part"
        with open(tmp, "wb") as handle:
            handle.write(data)
        os.replace(tmp, path)
        try:
            os.chmod(path, 0o600)
        except OSError:
            pass
        return {"name": name, "path": path, "displayPath": self.display_root() + "/" + name, "size": len(data)}

    def resolve(self, name):
        """把工作区内的相对名解析成真实路径；任何逃逸都抛 UnsafePath。"""
        if not name or name.startswith("/") or "\\" in name:
            raise UnsafePath(name)
        candidate = os.path.realpath(os.path.join(self.root, name))
        if candidate != self.root and not candidate.startswith(self.root + os.sep):
            raise UnsafePath(name)
        return candidate

    def delete(self, name):
        """删除工作区文件；越界路径抛 UnsafePath，不存在返回 False。"""
        target = self.resolve(name)
        if not os.path.isfile(target):
            return False
        os.remove(target)
        return True

    def list_files(self):
        entries = []
        for entry in os.scandir(self.root):
            if entry.is_file(follow_symlinks=False):
                info = entry.stat(follow_symlinks=False)
                entries.append({
                    "name": entry.name,
                    "size": info.st_size,
                    "mtime": int(info.st_mtime),
                    "mime": mimetypes.guess_type(entry.name)[0] or "application/octet-stream",
                })
        entries.sort(key=lambda item: item["mtime"], reverse=True)
        return entries


def parse_multipart_upload(content_type, body, max_bytes):
    """解析单文件 multipart/form-data，返回 (filename, bytes)。"""
    if len(body) > max_bytes:
        raise FileTooLarge(len(body))
    raw = b"Content-Type: " + content_type.encode("utf-8", "replace") + b"\r\n\r\n" + body
    message = email.message_from_bytes(raw)
    if not message.is_multipart():
        raise BadUpload("not_multipart")
    for part in message.walk():
        if part.get_content_maintype() == "multipart":
            continue
        disposition = part.get("Content-Disposition") or ""
        if "form-data" not in disposition:
            continue
        filename = part.get_filename()
        if not filename:
            continue
        data = part.get_payload(decode=True)
        if data is None:
            raise BadUpload("undecodable_part")
        if len(data) > max_bytes:
            raise FileTooLarge(len(data))
        return filename, data
    raise BadUpload("no_file_part")
