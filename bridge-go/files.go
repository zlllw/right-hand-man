package main

import (
	"errors"
	"fmt"
	"mime"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

const maxUploadBytes = 20 * 1024 * 1024

var (
	errUnsafePath = errors.New("unsafe_path")
	errNoFilePart = errors.New("no_file_part")
)

type fileEntry struct {
	Name  string `json:"name"`
	Size  int64  `json:"size"`
	MTime int64  `json:"mtime"`
	Mime  string `json:"mime"`
}

// Workspace is $HOME/rhmdir: the folder shared by the user and the agent.
type Workspace struct {
	root     string
	maxBytes int64
}

func newWorkspace(root string, maxBytes int64) (*Workspace, error) {
	if maxBytes <= 0 {
		maxBytes = maxUploadBytes
	}
	if err := os.MkdirAll(root, 0o755); err != nil {
		return nil, err
	}
	abs, err := filepath.Abs(root)
	if err != nil {
		return nil, err
	}
	resolved, err := filepath.EvalSymlinks(abs)
	if err != nil {
		return nil, err
	}
	return &Workspace{root: resolved, maxBytes: maxBytes}, nil
}

func (w *Workspace) MaxBytes() int64 { return w.maxBytes }

func (w *Workspace) DisplayRoot() string {
	home, err := os.UserHomeDir()
	if err == nil && strings.HasPrefix(w.root, home+string(os.PathSeparator)) {
		return "~" + strings.TrimPrefix(w.root, home)
	}
	return w.root
}

// resolve normalizes a workspace-relative name and rejects any escape.
func (w *Workspace) resolve(name string) (string, error) {
	if name == "" || strings.HasPrefix(name, "/") || strings.Contains(name, `\`) {
		return "", errUnsafePath
	}
	candidate := filepath.Join(w.root, name)
	resolved, err := filepath.EvalSymlinks(candidate)
	if err != nil {
		dir := filepath.Dir(candidate)
		if resolvedDir, dirErr := filepath.EvalSymlinks(dir); dirErr == nil {
			resolved = filepath.Join(resolvedDir, filepath.Base(candidate))
		} else {
			resolved = filepath.Clean(candidate)
		}
	}
	if resolved != w.root && !strings.HasPrefix(resolved, w.root+string(os.PathSeparator)) {
		return "", errUnsafePath
	}
	return resolved, nil
}

func sanitizeFilename(raw string) (string, error) {
	if raw == "" {
		return "", errors.New("missing_filename")
	}
	name := strings.ReplaceAll(raw, `\`, "/")
	if index := strings.LastIndex(name, "/"); index >= 0 {
		name = name[index+1:]
	}
	name = strings.Map(func(r rune) rune {
		if r < 0x20 {
			return -1
		}
		return r
	}, name)
	name = strings.TrimSpace(name)
	if name == "" || name == "." || name == ".." || strings.HasPrefix(name, ".") {
		return "", errors.New("invalid_filename")
	}
	if runes := []rune(name); len(runes) > 200 {
		name = string(runes[len(runes)-200:])
	}
	return name, nil
}

func uniqueName(directory, filename string) string {
	ext := filepath.Ext(filename)
	base := strings.TrimSuffix(filename, ext)
	candidate := filename
	for counter := 2; ; counter++ {
		if _, err := os.Lstat(filepath.Join(directory, candidate)); errors.Is(err, os.ErrNotExist) {
			return candidate
		}
		candidate = fmt.Sprintf("%s (%d)%s", base, counter, ext)
	}
}

func (w *Workspace) SaveUpload(filename string, data []byte) (map[string]any, error) {
	safe, err := sanitizeFilename(filename)
	if err != nil {
		return nil, err
	}
	name := uniqueName(w.root, safe)
	path := filepath.Join(w.root, name)
	tmp := path + ".part"
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return nil, err
	}
	if err := os.Rename(tmp, path); err != nil {
		os.Remove(tmp)
		return nil, err
	}
	_ = os.Chmod(path, 0o600)
	return map[string]any{
		"name":        name,
		"path":        path,
		"displayPath": w.DisplayRoot() + "/" + name,
		"size":        len(data),
	}, nil
}

func (w *Workspace) ResolveDownload(name string) (string, error) {
	return w.resolve(name)
}

func (w *Workspace) Delete(name string) (bool, error) {
	target, err := w.resolve(name)
	if err != nil {
		return false, err
	}
	info, err := os.Lstat(target)
	if err != nil || !info.Mode().IsRegular() {
		return false, nil
	}
	if err := os.Remove(target); err != nil {
		return false, nil
	}
	return true, nil
}

func (w *Workspace) ListFiles() []fileEntry {
	entries, err := os.ReadDir(w.root)
	if err != nil {
		return []fileEntry{}
	}
	files := make([]fileEntry, 0, len(entries))
	for _, entry := range entries {
		if !entry.Type().IsRegular() {
			continue
		}
		info, err := entry.Info()
		if err != nil {
			continue
		}
		files = append(files, fileEntry{
			Name:  entry.Name(),
			Size:  info.Size(),
			MTime: info.ModTime().Unix(),
			Mime:  guessMime(entry.Name()),
		})
	}
	sort.SliceStable(files, func(i, j int) bool { return files[i].MTime > files[j].MTime })
	return files
}

var mimeByExt = map[string]string{
	".txt":  "text/plain",
	".md":   "text/markdown",
	".json": "application/json",
	".html": "text/html",
	".htm":  "text/html",
	".css":  "text/css",
	".js":   "text/javascript",
	".xml":  "text/xml",
	".csv":  "text/csv",
	".png":  "image/png",
	".jpg":  "image/jpeg",
	".jpeg": "image/jpeg",
	".gif":  "image/gif",
	".webp": "image/webp",
	".svg":  "image/svg+xml",
	".pdf":  "application/pdf",
	".zip":  "application/zip",
	".mp3":  "audio/mpeg",
	".mp4":  "video/mp4",
	".wasm": "application/wasm",
}

func guessMime(name string) string {
	ext := strings.ToLower(filepath.Ext(name))
	if value, ok := mimeByExt[ext]; ok {
		return value
	}
	if value := mime.TypeByExtension(ext); value != "" {
		if index := strings.IndexByte(value, ';'); index >= 0 {
			value = strings.TrimSpace(value[:index])
		}
		return value
	}
	return "application/octet-stream"
}
