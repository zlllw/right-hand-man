package main

import (
	"context"
	"flag"
	"fmt"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
)

func main() {
	port := flag.Int("port", 0, "0 = 自动选空闲端口")
	adapterName := flag.String("adapter", "fake", "fake 或 opencode")
	ocPort := flag.Int("oc-port", 4096, "opencode serve 的端口")
	portFile := flag.String("port-file", "", "端口文件路径（真机为 $HOME/.bridge/port）")
	workspaceFlag := flag.String("workspace", "", "文件工作区根目录（默认 $HOME/rhmdir；none 关闭 /files）")
	flag.Parse()

	var adapter Adapter
	switch *adapterName {
	case "fake":
		adapter = newFakeAdapter()
	case "opencode":
		adapter = newOpencodeAdapter(*ocPort)
	default:
		fmt.Fprintf(os.Stderr, "未知 adapter：%s（可选 fake / opencode）\n", *adapterName)
		os.Exit(2)
	}

	var workspace *Workspace
	if *workspaceFlag != "none" {
		root := *workspaceFlag
		if root == "" {
			home, err := os.UserHomeDir()
			if err != nil {
				fmt.Fprintf(os.Stderr, "无法确定 HOME：%v\n", err)
				os.Exit(2)
			}
			root = filepath.Join(home, "rhmdir")
		}
		var err error
		workspace, err = newWorkspace(root, maxUploadBytes)
		if err != nil {
			fmt.Fprintf(os.Stderr, "无法准备工作区 %s：%v\n", root, err)
			os.Exit(2)
		}
	}

	bridge := newBridge(adapter, workspace)
	listener, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", *port))
	if err != nil {
		fmt.Fprintf(os.Stderr, "监听失败：%v\n", err)
		os.Exit(2)
	}
	address := listener.Addr().(*net.TCPAddr)

	if err := adapter.Start(); err != nil {
		listener.Close()
		fmt.Fprintf(os.Stderr, "Bridge 启动失败：%v\n", err)
		os.Exit(2)
	}

	if *portFile != "" {
		if err := writePortFile(*portFile, address.Port); err != nil {
			listener.Close()
			adapter.Stop()
			fmt.Fprintf(os.Stderr, "写端口文件失败：%v\n", err)
			os.Exit(2)
		}
	}

	fmt.Printf("bridge listening on 127.0.0.1:%d (adapter=%s)\n", address.Port, adapter.Name())

	server := &http.Server{Handler: newHandler(bridge)}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	go func() {
		if err := server.Serve(listener); err != nil && err != http.ErrServerClosed {
			fmt.Fprintf(os.Stderr, "服务退出：%v\n", err)
		}
	}()
	<-ctx.Done()
	_ = server.Close()
	adapter.Stop()
}

func writePortFile(path string, port int) error {
	if directory := filepath.Dir(path); directory != "." {
		if err := os.MkdirAll(directory, 0o755); err != nil {
			return err
		}
	}
	if err := os.WriteFile(path, []byte(fmt.Sprintf("%d\n", port)), 0o600); err != nil {
		return err
	}
	_ = os.Chmod(path, 0o600)
	return nil
}
