# CrossServerChat

一个简单的 Fabric 服务端跨服聊天 MOD，不依赖 Velocity/BungeeCord、Discord、数据库或消息队列等任何其他设施

选一台 Fabric 服务器作为 `host`，其余 Fabric 服务器作为 `client` 连接到host，共同转发消息。

纯服务端MOD，客户端无需安装。

## 配置文件

位于`config/cross-server-chat.json`。

`host` 配置示例：

```json
{
  "mode": "host",
  "serverName": "survival",
  "bindAddress": "0.0.0.0",
  "host": "127.0.0.1",
  "port": 8192,
  "sharedSecret": "replace-with-a-long-random-secret",
  "messageFormat": "[%server%] <%player%> %message%",
  "connectTimeoutSeconds": 5,
  "reconnectDelaySeconds": 5
}
```

`client` 配置示例：

```json
{
  "mode": "client",
  "serverName": "creative",
  "bindAddress": "0.0.0.0",
  "host": "10.0.0.10",
  "port": 8192,
  "sharedSecret": "replace-with-a-long-random-secret",
  "messageFormat": "[%server%] <%player%> %message%",
  "connectTimeoutSeconds": 5,
  "reconnectDelaySeconds": 5
}
```

- `messageFormat`字段：占位符目前支持 %server%、%player% 和 %message%
- `sharedSecret`字段：启用前请替换为复杂密码

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
