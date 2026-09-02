# CrossServerChat

一个基于 Redis 的 Fabric 服务端跨服聊天和 Tab 玩家列表同步 MOD。所有 Minecraft 服务器都是对等节点，不再区分 host/client。

消息经 AES-256-GCM 加密和认证后才会发布到 Redis；Redis 只能看到 Base64 密文。

纯服务端MOD，客户端无需安装。

## Redis 部署

需要 Redis 7.4 或更高版本。

以下是 `redis.conf` 示例：

```conf
bind 0.0.0.0
protected-mode yes
port 6379
save ""
appendonly no
```

Docker Compose 示例：

```yaml
services:
  redis:
    image: redis:8-alpine
    restart: unless-stopped
    command: ["redis-server", "/usr/local/etc/redis/redis.conf"]
    volumes:
      - ./redis.conf:/usr/local/etc/redis/redis.conf:ro
    ports:
      - "6379:6379"
```

## 配置文件

位于`config/cross-server-chat.yaml`。

所有服务器使用相同结构的配置，只需保证 `serverName` 唯一：

```yaml
# Enables Redis-backed cross-server chat on this server.
enabled: true
# Unique name used to identify this Minecraft server in relayed messages.
serverName: "Survival"
# Redis connection. Keep Redis on a trusted private network; TLS is not used.
redisHost: "10.0.0.10"
redisPort: 6379
# Redis ACL username. Use "default" for password-only authentication.
redisUsername: "crossserverchat"
redisPassword: "REPLACE_WITH_A_LONG_RANDOM_PASSWORD"
# Shared secret used to encrypt relay traffic.
# Use the same value on every CrossServerChat server in this network.
sharedSecret: "XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"
# Remote message display rules on this server.
# MiniMessage format. Available placeholders: %server%, %player%, %message%.
message-relay:
  # Displays remote player chat messages.
  - player-chat: enabled
    messageFormat: "<gray>[%server%]</gray> <%player%> %message%"
  # Displays remote player join messages.
  - player-join: enabled
    messageFormat: "<gray>[%server%]</gray> <yellow>%player% joined the game</yellow>"
  # Displays remote player leave messages.
  - player-leave: enabled
    messageFormat: "<gray>[%server%]</gray> <yellow>%player% left the game</yellow>"
  # Displays remote player death messages.
  - player-death: enabled
    messageFormat: "<gray>[%server%]</gray> %message%"
# Shows players from other connected servers in the vanilla Tab list.
player-list-sync:
  enabled: true
  # MiniMessage format. Available placeholders: %server%, %player%.
  displayFormat: "%player% <green>[%server%]</green>"
# Maximum time in seconds a Redis connection attempt may take.
connectTimeoutSeconds: 5
# Delay in seconds before a disconnected subscriber attempts to reconnect.
reconnectDelaySeconds: 5

# Do not change this number.
version: 6
```

- `message-relay` 列表只控制本服是否显示收到的远程消息；发送端始终发送四类事件
- 每一项可设为 `enabled` 或 `disabled`
- 每项的 `messageFormat` 均支持 `%server%`、`%player%` 和 `%message%`
- `player-death` 的 `%message%` 是原版死亡消息；`player-join` 和 `player-leave` 的 `%message%` 为空
- 所有节点的 `sharedSecret` 必须相同，`serverName` 必须不同
- `sharedSecret` 启用前请替换为至少 32 字符的随机密码
- `player-list-sync.enabled` 控制是否在原版 Tab 列表中显示其他服务器的玩家
- `player-list-sync.displayFormat` 支持 `%server%`、`%player%` 和 MiniMessage 格式

修改配置后，可执行 `/crossserverchat reload` 命令重载配置，无需重启服务器。

### 消息格式自定义

每项的 `messageFormat` 使用[MiniMessage格式](https://docs.papermc.io/adventure/minimessage/format/)，支持命名颜色、RGB颜色、粗体、渐变和彩虹等文本样式。以下以 `player-chat` 为例：

默认格式：

```yaml
messageFormat: "<gray>[%server%]</gray> <%player%> %message%"
```

RGB颜色和粗体：

```yaml
messageFormat: "<#55ffaa><bold>[%server%]</bold></#55ffaa> <yellow>%player%</yellow><gray>: %message%"
```

渐变彩虹字体：

```yaml
messageFormat: "<gradient:#5e4fa2:#f79459>[%server%]</gradient> <rainbow>%player%</rainbow><gray>: <white>%message%"
```

完整语法和可用标签参阅[MiniMessage官方文档](https://docs.papermc.io/adventure/minimessage/format/)。可以使用[MiniMessage Web Viewer](https://webui.advntr.dev/)在线预览文本格式。

## 构建

```bash
./gradlew build
```

Windows：

```powershell
.\gradlew.bat build
```

## License

[MIT](LICENSE)
