# 本机开发与 Minecraft 离屏渲染

Minecraft 的 RGB 和深度来自真实客户端的 OpenGL framebuffer，需要持续
渲染，但不需要占用物理显示器、键盘或鼠标。

## 推荐启动方式

保持平时的 rbnx 启动命令，设置：

```bash
export RBNX_MINECRAFT_USE_NVIDIA=1
export MC_NVIDIA_DISPLAY=:48
```

启动入口自动创建独立 NVIDIA Xorg，客户端结束时清理它。不需要先启动
图形窗口或执行 sudo。也可以在单独的 tmux 窗口里保持显示服务器运行：

```bash
./scripts/run-nvidia-display.sh
```

随后启动 rbnx，它会复用该显示服务器。前台按 Ctrl-C 停止显示服务器；
请先停止使用它的 Minecraft 客户端。游戏画面可通过 rbnx 的
`http://127.0.0.1:8765/` 查看。

## 原因与修复

原来的 `sudo Xorg :48 ...` 只设置了 `UseDisplayDevice none`。
这个选项关闭显示输出，却没有禁止 Xorg 切换 Linux 虚拟终端或发现
物理输入设备，所以另一个显示编号仍可能影响本机桌面。

现在使用普通用户直接运行原生 Xorg 二进制：

- `-seat minecraft-48`：使用独立 seat，绕过 seat0 的物理终端管理。
- `-novtswitch -sharevts`：禁止自动切换虚拟终端，并避免独占它。
- `AutoAddDevices/AutoEnableDevices false`：不自动添加或启用物理键鼠。
- `AutoAddGPU/AutoBindGPU false` 和 `-isolateDevice`：限定所选 NVIDIA GPU。
- `UseDisplayDevice none`：只保留虚拟 framebuffer，不驱动显示器。
- 独立 Xauthority cookie、`-nolisten tcp`：不使用桌面的认证文件。

客户端启动时同时设置 `pauseOnLostFocus:false` 和 `fullscreen:false`。
选择 NVIDIA 模式后，显示初始化失败会报错退出，不再自动回退到桌面。

## 排查

需要已安装 NVIDIA 的 Xorg/GLX 模块，以及 `xauth`、`mcookie`、`flock`、
`xdpyinfo`。Ubuntu 使用 `/usr/lib/xorg/Xorg`，避免经过要求控制台登录的
`Xorg.wrap`。启动失败时终端会打印 Xorg 日志末尾。

`MC_NVIDIA_DISPLAY` 接受 `48` 或 `:48`。认证文件默认在
`${XDG_RUNTIME_DIR:-$HOME/.cache/robonix}/minecraft-xauth-48`；连接已有显示
时优先使用它，避免误用 Wayland 桌面的 Xwayland 认证文件。

本方案在 Intel 桌面 + NVIDIA RTX 5070 Laptop GPU 的 Wayland 主机上验证。
其他驱动版本、桌面直接使用同一块 NVIDIA GPU 的机器仍需单独验证。
