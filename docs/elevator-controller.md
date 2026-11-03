# 电梯控制器：六控制器与 fill 门（0.3.0）

控制器方块 ID 仍为 `robonix_minecraft:elevator_controller`，每个方块向 `facing` 指定的一面输出 0 或 15。一部电梯可以绑定多个控制器。两层方案使用六个控制器，每个控制器连接一个「脉冲、无条件、需要红石」的命令方块：

| 控制器 | 对应动作 |
| --- | --- |
| 1 | 一楼开门，fill 清除门体 |
| 2 | 一楼关门，fill 恢复门体 |
| 3 | 一楼轿厢 → 二楼轿厢 |
| 4 | 二楼开门，fill 清除门体 |
| 5 | 二楼关门，fill 恢复门体 |
| 6 | 二楼轿厢 → 一楼轿厢 |

六个动作都使用短脉冲。Python 先断电并等待两个服务器 tick，再通电至少四个 tick，最后断电；命令产生的门体或玩家位置变化会保持。Mod 生产代码只控制红石和查询状态，门与运输由命令方块执行。

## 构建与更新

```bash
JAVA_HOME="$HOME/.local/share/jdks/jdk-17.0.19+10" ./gradlew build runGameTestServer
python3 -m unittest discover -s tests -p test_elevator_controller.py -v
```

新版产物为 `build/libs/robonix_minecraft-0.3.0.jar`。统一 Mod 包可安装在服务器和客户端，`mods/` 中仅保留一个版本。服务端重启后自动提供 `127.0.0.1:25577` 的电梯接口；它只接受控制器读写及相关状态查询，不提供任意游戏命令。服务端与电梯 Robonix 同机部署时，门控无需任何玩家在线。服务器使用 Minecraft 1.20.4 / Forge 49.2.0 并开启 `enable-command-block=true`。原有 Forge 通道保持协议版本 2；仅该旧客户端转发路径要求玩家 OP 2。

2026-10-23 验证：构建成功，6 项服务端游戏测试、19 项机器人接入测试、135 项 primitive 回归测试通过。已在用户现有服务器完成实际 Robonix 开关门验证：本机客户端接口 25576 关闭，仅通过服务器 25577 触发红石线路、验证门区域，并正常复位。完整跨楼层地图与导航验收仍待完成。

[多人部署说明](elevator-multiplayer.md)介绍升级和端口配置。电梯进程默认直接连接服务器，机器人客户端仅用于机器人自身的感知和移动，不参与电梯的控制转发。若电梯进程位于另一台主机，可通过 SSH 本地转发连接服务端 loopback；接口本身固定监听本机。

设施所在区块必须保持加载并能正常 tick。无人在线且设施远离出生区时，可用服务器 `forceload` 配置设施区块；接口不会自行加载或生成区块。

## 命令方块示例

以下坐标是搭建示例，必须替换为实际场景；命令方块内不加开头的 `/`。假设一楼门为 `(10,64,20)` 至 `(11,66,20)` 的玻璃区域，二楼同一位置高 8 格：

一楼开门、关门：

```mcfunction
fill 10 64 20 11 66 20 minecraft:air replace minecraft:glass
fill 10 64 20 11 66 20 minecraft:glass replace minecraft:air
```

二楼开门、关门：

```mcfunction
fill 10 72 20 11 74 20 minecraft:air replace minecraft:glass
fill 10 72 20 11 74 20 minecraft:glass replace minecraft:air
```

一楼上行与二楼下行，限定机器人身份和来源轿厢范围：

```mcfunction
execute as @a[name=robonix_agent,x=10,y=64,z=21,dx=2,dy=2,dz=2] run tp @s 11.5 72 22.5 180 0
execute as @a[name=robonix_agent,x=10,y=72,z=21,dx=2,dy=2,dz=2] run tp @s 11.5 64 22.5 180 0
```

运输目标位置应使玩家完整处于目标轿厢内部，朝向应与目标层地图中的轿厢标注一致。保持命令方块 `UpdateLastExecution:1b`，以便查询新执行 tick 和成功次数。调试时只触发已明确绑定的线路。

## 绑定配置

完整模板在机器人包的 `config/elevator.example.json`，仍需填写真实保存地图与 Scene 区域绑定。每层新增：

- `door_open_controller`：开门命令控制器的原生 ID。
- `door_close_controller`：关门命令控制器的原生 ID。
- `door_open_command_block` / `door_close_command_block`：可选命令方块 ID；通过红石线连接时明确填写，直接相邻时可省略。
- `door_region`：门体的世界维度、整数 `min` / `max` 和 `closed_block`。
- `cabin_bounds`：轿厢可容纳玩家的世界方块范围，用于验证真实运输结果。
- `transport`：目标楼层到运输控制器 ID 的映射；可选 `transport_command_blocks` 按目标层绑定通过红石线连接的命令方块。

例如门区域：

```json
{"dimension":"minecraft:overworld","min":[10,64,20],"max":[11,66,20],"closed_block":"minecraft:glass"}
```

`door_region` 和 `cabin_bounds` 的两个角点均包含在范围内，与 `fill` 一致。门最多 4096 格；不能用空气作为关门材质。整个区域必须在合法、已加载的区块中。`cabin_bounds` 表示完整空间，而非单个站立点；服务端的玩家碰撞盒必须全部处于 `[min, max + 1]` 内。

`elevator_mod_host` / `elevator_mod_port` 默认 `127.0.0.1` / `25577`，不会回退到客户端。`initial_floor` 指定固定轿厢方案的初始服务楼层（未指定时选首个配置楼层）；电梯启动无需读取机器人地图。`minecraft_player` 指定实际机器人用户名，与 Robonix 的 `robot_id` 分开，仅运输确认需要该玩家在线。六个控制器应有各自的原生 ID，例如 `minecraft:overworld@4,64,1`。控制器在被机器人客户端近距离观察后，可用下列命令列出（这只是可选的客户端观察工具；已知坐标可直接填写 ID，电梯启动不依赖该步骤）：

```bash
python3 scripts/elevator_controller.py list
```

配置模板中的 `null` 范围和控制器占位字符串必须替换，未绑定会在连接前报错。旧 `door_controller` / `doors` 配置需迁移为以上字段。[机器人接入说明](../../robot-syswonder-minecraft_bot/docs-elevator.md)解释保存地图和 Scene 标注。

## 门与到站的确认

服务器按真实方块返回门状态：全部空气为 `open`，全部指定门体方块为 `closed`，混合或其他材质为 `unknown`。异常状态不会被当作已关闭，适配器会报错停止。若门已经处于要求状态，适配器直接返回；否则要求新命令执行成功且门体真的改变。

运输前必须确认来源和目标门已关闭，且指定机器人完整进入来源轿厢。到站要求同一玩家 UUID、新的成功命令执行记录，以及玩家完整进入目标 `cabin_bounds`。只达到相同高度、落在轿厢外、或只收到红石回执都不算到站。机器人仍等待电梯到站报告后切图，门与轿厢的世界坐标检查不会自动切换机器人的楼层。

## 本地调试

读取一楼开门控制器，同时观察门区域：

```bash
python3 scripts/elevator_controller.py get --pos 4 64 1 \
  --door-min 10 64 20 --door-max 11 66 20 --closed-block minecraft:glass
python3 scripts/elevator_controller.py pulse --pos 4 64 1 \
  --door-min 10 64 20 --door-max 11 66 20
python3 scripts/elevator_controller.py pulse --pos 4 64 3 \
  --door-min 10 64 20 --door-max 11 66 20
```

脚本默认连接服务端 `127.0.0.1:25577`，用 `--host` / `--port` 或 `RBNX_MINECRAFT_ELEVATOR_PORT` 修改连接地址。`list` 单独读取客户端的 `--client-port`（默认 25576）；`ride` 使用服务端玩家观测，需要 `--player`。独立服务器应保持运行。脉冲结束或等待失败时会尝试断电；强制终止进程、断线时不保证复位，重试前使用 `off`。调试脚本的 `ride --source ... --target ...` 仍是脚部坐标烟雾测试，完整 adapter 使用上述碰撞盒验证。

本地 JSON-lines 协议示例：

```json
{"id":"1","cmd":"elevator_controller_get","dimension":"minecraft:overworld","x":4,"y":64,"z":1,"door_region":{"dimension":"minecraft:overworld","min":[10,64,20],"max":[11,66,20],"closed_block":"minecraft:glass"}}
```

请求可添加 `player`（实际机器人用户名）和 `command_block: {"x":84,"y":-55,"z":43}`（同维度中的显式命令方块位置）。未指定玩家或玩家离线时 `player_online=false`，仍可控制门；运输必须确认该玩家在线。响应沿用 `type/id/ok/result`，增加 `door_region` 的 `state`、`total_blocks`、`air_blocks`、`closed_blocks`、`other_blocks`，以及 `player.uuid` 和真实碰撞盒 `player.bounds.min/max`。控制器输出信息和相邻命令方块的 `command_tick` / `command_success` 保留。旧铁门 `doors` 查询仅保留作底层兼容，新电梯适配器使用 `door_region`。
