# CrossServerChat

一个简单的 Fabric 服务端跨服聊天 MOD，不依赖 Velocity/BungeeCord、Discord、数据库或消息队列等任何其他设施

选一台 Fabric 服务器作为 `host`，其余 Fabric 服务器作为 `client` 连接到host，共同转发消息。

纯服务端MOD，客户端无需安装。

## 配置文件

位于`config/cross-server-chat.yaml`。启动时如果只存在旧版 `cross-server-chat.json`，MOD 会自动迁移其配置，成功写入 YAML 后删除旧文件。

`host` 配置示例：

```yaml
# Mode: disabled, host, or client.
mode: "host"
# Unique name used to identify this Minecraft server in chat messages.
serverName: "Survival"
# Network address the host mode listens on.
bindAddress: "0.0.0.0"
# Address of the relay host used by client mode.
host: "127.0.0.1"
# TCP port used by the relay host and all clients.
port: 8192
# Shared secret used to encrypt relay traffic. Use the same value on every server.
sharedSecret: "XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"
# MiniMessage format for remote chat. Available placeholders: %server%, %player%, %message%.
messageFormat: "<gray>[%server%]</gray> <%player%> %message%"
# Maximum time in seconds a client waits while connecting to the host.
connectTimeoutSeconds: 5
# Delay in seconds before a disconnected client attempts to reconnect.
reconnectDelaySeconds: 5

version: 2
```

`client` 配置示例：

```yaml
# Mode: disabled, host, or client.
mode: "client"
# Unique name used to identify this Minecraft server in chat messages.
serverName: "Creative"
# Network address the host mode listens on.
bindAddress: "0.0.0.0"
# Address of the relay host used by client mode.
host: "10.0.0.10"
# TCP port used by the relay host and all clients.
port: 8192
# Shared secret used to encrypt relay traffic. Use the same value on every server.
sharedSecret: "XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"
# MiniMessage format for remote chat. Available placeholders: %server%, %player%, %message%.
messageFormat: "<gray>[%server%]</gray> <%player%> %message%"
# Maximum time in seconds a client waits while connecting to the host.
connectTimeoutSeconds: 5
# Delay in seconds before a disconnected client attempts to reconnect.
reconnectDelaySeconds: 5

version: 2
```

- `messageFormat`字段：占位符目前支持 %server%、%player% 和 %message%
- `sharedSecret`字段：启用前请替换为复杂密码

### 消息格式自定义

`messageFormat`使用[MiniMessage格式](https://docs.papermc.io/adventure/minimessage/format/)，支持命名颜色、RGB颜色、粗体、渐变和彩虹等文本样式。举一点例子：

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
