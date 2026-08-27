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
  "serverName": "Survival",
  "bindAddress": "0.0.0.0",
  "host": "127.0.0.1",
  "port": 8192,
  "sharedSecret": "XXXXXXXXXXXXXXXXX",
  "messageFormat": "<gray>[%server%]</gray> <%player%> %message%",
  "connectTimeoutSeconds": 5,
  "reconnectDelaySeconds": 5
}
```

`client` 配置示例：

```json
{
  "mode": "client",
  "serverName": "Creative",
  "bindAddress": "0.0.0.0",
  "host": "10.0.0.10",
  "port": 8192,
  "sharedSecret": "XXXXXXXXXXXXXXXXXXXXXXXXXXXXXX",
  "messageFormat": "<gray>[%server%]</gray> <%player%> %message%",
  "connectTimeoutSeconds": 5,
  "reconnectDelaySeconds": 5
}
```

- `messageFormat`字段：占位符目前支持 %server%、%player% 和 %message%
- `sharedSecret`字段：启用前请替换为复杂密码

### 消息格式自定义

`messageFormat`使用[MiniMessage格式](https://docs.papermc.io/adventure/minimessage/format/)，支持命名颜色、RGB颜色、粗体、渐变和彩虹等文本样式。举一点例子：

默认格式：

```json
"messageFormat": "<gray>[%server%]</gray> <%player%> %message%"
```

RGB颜色和粗体：

```json
"messageFormat": "<#55ffaa><bold>[%server%]</bold></#55ffaa> <yellow>%player%</yellow><gray>: %message%"
```

渐变彩虹字体：

```json
"messageFormat": "<gradient:#5e4fa2:#f79459>[%server%]</gradient> <rainbow>%player%</rainbow><gray>: <white>%message%"
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
