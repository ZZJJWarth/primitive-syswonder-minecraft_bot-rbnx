# 多人服务器部署

当前版本为 **0.3.0**，提供独立服务端电梯接口：

```text
电梯 Robonix → 服务器 127.0.0.1:25577 → 控制器与命令方块
机器人 Robonix → 客户端 127.0.0.1:25576 → 机器人感知与移动
```

电梯不需要额外的 Minecraft 客户端。服务端接口只绑定 loopback，不提供任意命令或 RCON；同机电梯进程直接连接，跨主机可使用 SSH 本地端口转发。默认端口由服务器进程的 `RBNX_MINECRAFT_ELEVATOR_PORT` 指定，`0` 禁用。设施区块须保持加载和 tick。

## 安装

1. 使用 Minecraft Java 1.20.4、Forge 49.2.0；本包构建和运行使用 Java 17。
2. 在本包目录构建：

   ```bash
   JAVA_HOME="$HOME/.local/share/jdks/jdk-17.0.19+10" ./gradlew build runGameTestServer
   ```

3. 正常停服保存，将 `build/libs/robonix_minecraft-0.3.0.jar` 放入服务器的 `mods/`，以及参与此场景的 Forge 客户端的 `mods/`。建议两端使用同一次构建的 jar；旧版备份后移出 `mods/`。0.3.0 保留客户端 Forge 通道协议 2，新增的服务端 TCP 接口独立于该通道。
4. 在服务器 `server.properties` 中设置 `enable-command-block=true`，再启动服务器。电梯 adapter 配置 `elevator_mod_host` 和 `elevator_mod_port`（默认 127.0.0.1 / 25577），机器人客户端接口仍使用 25576。
5. 直接服务端控制不借用任何玩家权限；本地接口仅接受本机连接。只有使用旧 Forge 客户端转发控制时，才需要该玩家至少 OP 2。

机器人需要感知和移动时才启动游戏客户端。可通过启动器加入，或运行开发客户端：

```bash
DISPLAY=:48 MC_HOST=127.0.0.1 MC_PORT=25565 MC_USERNAME=robonix_agent \
  ./scripts/run-client.sh
```

`DISPLAY` 应替换为本机可用的显示器；`MC_HOST` 是多人游戏服务器地址。脚本的离线用户名适用于已配置为离线模式的服务器；使用正版验证的服务器应通过已登录账号的 Forge 启动器加入。

## 检查连接

独立服务器启动、服务端 25577 监听后，读取一个已放置控制器（坐标替换为场景实际值）：

```bash
python3 scripts/elevator_controller.py get --pos 4 64 1
python3 scripts/elevator_controller.py pulse --pos 4 64 1
python3 scripts/elevator_controller.py off --pos 4 64 1
```

`get` 返回服务端 `game_time`、控制器输出、玩家和可选门状态。`pulse` 复位后按服务器游戏 tick 产生脉冲，触发相邻命令方块。服务器不存在该 Mod、机器人没有 OP 权限、区块未加载或目标不是控制器时会报错。尚未加入服务器的客户端不能控制电梯。

控制器和轿厢附近区块应保持加载。运输命令方块须为「脉冲、无条件、需要红石」，限定机器人身份与来源轿厢区域，并保持执行记录 `UpdateLastExecution:1b`。红石回执只表示输出已设置，实际到站需按[控制器说明](elevator-controller.md)检查真实反馈。完整保存地图与乘梯部署见[机器人接入说明](../../robot-syswonder-minecraft_bot/docs-elevator.md)。

## 本机部署记录（2026-10-23）

- 独立服务器目录：`/home/warth/Desktop/minecraft-server`。
- 服务器已安装 `mods/robonix_minecraft-0.1.0.jar`，并正常保存重启；使用 Java 17，在 `tmux` 的 `robonix:0.0` 中运行。
- 客户端已安装 `/home/warth/.minecraft/mods/robonix_minecraft-0.1.0.jar`。旧 jar 备份于 `/home/warth/.minecraft/mod-backups/20261023-134225/`。
- 既有配置已启用命令方块；`robonix_agent` 的既有 OP 4 满足控制要求。
- 两端 jar 的 SHA-256 为 `0cda2965e13ed9f99cd2f2fe5b04e3f3befe74f87adb9c77b29c67e1ed76f976`。
- Forge 构建和 3 项独立世界服务端游戏测试通过。完整乘梯场景的地图、门和线路绑定仍需按实际场景配置。
- 已在现有独立服务器完成实际网络验证：开发客户端 `robonix_agent` 经 `127.0.0.1:25565` 加入，本地 JSON 接口成功读取控制器、输出红石、触发服务端命令方块并复位。测试命令为只读的 `time query gametime`，服务端返回 `command_success=1`、新执行 tick `18555041`、复位后 `powered=false`。在原本为空气的位置放置的临时方块已清理。
- 验证客户端运行在显示器 `:48`、`tmux` 窗口 `robonix:4`；开发客户端使用本包当前源码，普通 Forge 启动器使用上述 `.minecraft/mods/` 中的 jar。验证结果保存于 `build/multiplayer-deployment-probe.json`。
- 本次验证未执行玩家跨层传送，也未启动导航和建图；完整乘梯验收仍需正式场景绑定。


## 本机升级记录（2026-10-23 15:39）

- 服务器 Mod：`/home/warth/Desktop/minecraft-server/mods/robonix_minecraft-0.2.0.jar`。
- Ubuntu 客户端 Mod：`/home/warth/.minecraft/mods/robonix_minecraft-0.2.0.jar`。
- 两端旧版均备份到各自的 `mod-backups/20261023-153953/`，已从活动 `mods/` 中移除旧 jar。
- SHA-256：`a15583e87c19ed0e6278aab2183e80c587ded988589ddf82e096b7cac2a58791`。
- 更新前服务器已正常保存停止；升级后使用 Java 17 启动于 `tmux robonix:0`。开发客户端运行于 `robonix:4`、显示器 `:48`。
- 安装记录：`build/local-mod-upgrade-0.2.0.json`。本次仅更新本机，不会修改 Windows 文件。
- 升级后已实测客户端经 Forge 连接返回新的 `door_region` 状态、玩家 UUID 和完整碰撞盒。只读查询成功，临时探测控制器已清理。


## 独立服务端入口部署记录（2026-10-23 16:03）

- 服务器及本机客户端安装文件更新为 0.3.0，旧 jar 备份目录分别为各自的 `mod-backups/20261023-160324/`。
- 服务器正常保存重启，`127.0.0.1:25577` 已监听；本机 Minecraft 客户端没有启动，25576 关闭。
- 真实 Robonix Service 经标准生命周期激活，使用正式 `DoorControls.door` 和实际红石线路，成功打开和关闭用户门体：83 -60 26 至 83 -58 28，共 9 格，关闭材质为浅灰色混凝土。
- 使用显式命令方块位置，开门 84 -55 43、关门 82 -55 43；控制器仍为 84 -55 48 和 82 -55 48。未改动命令和接线。
- 测试最终门关闭、控制器断电，测试 Service 与独立 Atlas 已正常停止；游戏服务器持续运行。
- 实际绑定在机器人包 `config/elevator-door.local.json`，结果在 `runtime/door-test/result.json`。
