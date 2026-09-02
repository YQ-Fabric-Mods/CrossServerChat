# 跨服玩家列表同步设计

状态：已实现。

本文描述 CrossServerChat 的跨服玩家列表同步方案。这里的“玩家列表”仅指客户端按下 Tab 后看到的 Player Info 列表，不同步玩家实体、存档、背包、位置、经验、权限、计分板或其他游戏状态。

## 目标

- 原版客户端无需安装 MOD，即可在 Tab 列表中看到其他服务器的在线玩家。
- Redis 保存各服务器当前在线玩家快照，Pub/Sub 仅作为快照变更通知。
- 新启动或 Redis 重连的节点可以直接恢复完整远端列表，不依赖此前的加入、退出事件。
- 节点异常退出后，其玩家列表能够自动过期并从其他节点的 Tab 列表中移除。
- 远端玩家不进入本服 `PlayerList`，不影响在线人数、命令选择器、玩家数据或世界逻辑。
- 聊天转发协议和 `CrossServerChat:Message` 频道保持兼容。

## 非目标

- 不创建远端 `ServerPlayer` 或玩家实体。
- 不允许对远端玩家执行本服命令、传送或交互。
- 第一版不转发真实游戏模式、队伍、计分板或皮肤纹理。
- 第一版不专门处理玩家在服务器之间切换时的瞬时重复、缺失或 Player Info 数据包时序问题。
- 不保证显示超过原版客户端上限的条目。Minecraft 26.2 的 Tab 界面最多渲染 80 个条目，包括本服玩家。

## 总体设计

每个服务器只拥有并更新自己的 Redis 快照。其他服务器只能读取该快照，不能代替其修改玩家列表。

```text
本服 PlayerList
      |
      | 加入、退出、定时更新
      v
加密完整快照 -- HSET + HEXPIRE --> Redis
      |                             |
      +--------- PUBLISH ----------+
                                    |
                                    v
                         其他节点读取最新快照
                                    |
                                    v
                         合并 RemotePlayerDirectory
                                    |
                                    v
                         向本服客户端发送 Player Info
```

Redis 中的快照是事实来源，通知只是缓存失效信号。通知丢失不会永久造成列表不一致，因为节点会定时执行完整更新。

## Redis 数据模型

### 键和频道

| 名称                                  | Redis 类型      | 用途                                                       |
| ------------------------------------- | --------------- | ---------------------------------------------------------- |
| `CrossServerChat:PlayerList:Data`   | Hash            | field 为`serverId`，value 为加密完整快照，并设置字段 TTL |
| `CrossServerChat:PlayerListUpdated` | Pub/Sub channel | 通知其他节点重新读取指定服务器快照                         |

`serverId` 建议取 `SHA-256(serverName)` 的小写十六进制结果。它作为 Hash field，长度固定且不受服务器名称中的空格或分隔符影响；服务器名称仍保存在加密快照中。

不要使用 Redis `LIST`、逐玩家键或一个全局玩家集合。每服在 Hash 中只占一个 field，完整快照可以通过一次 `HSET` 原子替换，空列表也有明确表示。

`HSET` 会清除被覆盖 field 原有的 TTL，因此每次写入快照后都必须在同一个事务中重新执行 `HEXPIRE`。没有玩家变化的心跳只执行 `HEXPIRE`，不会覆盖 field。Pub/Sub channel 仍然独立存在，只负责变更通知，不能由 Hash 代替。

### 快照明文结构

快照先序列化，再使用 `sharedSecret` 加密，Redis 只保存 Base64 密文。

```json
{
  "protocol": 1,
  "serverId": "<sha256(serverName)>",
  "server": "Survival",
  "players": [
    {
      "uuid": "8667ba71-b85a-4004-af54-457a9734eed7",
      "name": "Steve",
      "latency": 42
    }
  ]
}
```

字段语义：

- `protocol`：PlayerListSync 协议版本，与现有聊天协议版本独立。
- `serverId`：由 `server` 计算所得
- `server`：配置中的唯一 `serverName`。
- `players`：本服完整在线玩家集合，写入前按 UUID 字典序排序，以便稳定比较。

### 通知明文结构

```json
{
  "protocol": 1,
  "serverId": "<sha256(serverName)>"
}
```

通知只表示指定 `serverId` 的 Redis 状态可能发生了变化。接收端收到任何有效通知后都必须执行 `HGET`：field 存在时使用当前快照，field 不存在时移除该服状态。通知不能直接携带或决定玩家列表状态。

## 数据同步和生命周期

Redis远端条目的TTL为30秒。TTL 大于心跳间隔，以容忍短暂的 GC、tick 卡顿或 Redis 抖动。

每个服务端MOD内存中保存一份serverId - value的哈希表变量（除了自己的），与Redis远端数据保持同步

- 每次收到其他服务器的通知时，始终尝试使用`HGET`拉取指定服务器快照，保存到变量，并检查差异，向本服玩家发送通知
- 如果`HGET`发现远端服务器不存在，删除本地的哈希表中对应的serverId变量，并向本服玩家发送通知
- reload或redis断线时，立刻清空本地数据，并向本服玩家发送所有远端服务器玩家的下线通知。

玩家列表同步管理器拥有一个贯穿服务器进程生命周期的专用单线程任务队列，同一时刻最多管理一个同步实例。除必须在 Minecraft 服务器线程执行的操作外，连接、Redis 命令、状态变更、心跳、全量更新和 reload 都由该队列串行、同步执行。外部事件只负责尝试向队列提交任务，不直接读写同步状态。

- 读取本服玩家列表时，队列向服务器线程提交读取任务，并同步阻塞等待结果。读取操作很短，不在服务器线程执行 Redis 或其他网络操作。
- 向玩家发送 Player Info 数据包时，队列向服务器线程提交单向发送任务，提交后立即继续，不等待执行结果，也不接收完成回调。
- 实例处于不接受普通任务的状态时，玩家加入退出、远端通知和定时任务不能入队，此时直接丢弃任务。
- 为避免订阅完成与 `HGETALL` 之间出现状态缺口，`HYDRATING` 阶段收到的 Redis 消息允许排入队列，并在初始化任务完成后处理；玩家事件和定时任务仍需等到 `ACTIVE`。

同步实例具有以下生命周期状态：

- `STOPPED`：尚未启动或当前实例已经完全关闭，不接受普通同步任务；同步管理器仍可接受启动任务。
- `CONNECTING`：正在建立 Redis 命令连接和 Pub/Sub 订阅，尚不对外提供玩家列表同步。
- `HYDRATING`：订阅已经成功，正在写入本服快照并通过 `HGETALL` 恢复远端完整状态。
- `ACTIVE`：初始化完成，可以处理玩家加入退出、通知、心跳和全量更新。
- `DISCONNECTED`：Redis 连接失效，本地远端视图已经清空，等待重连。
- `STOPPING`：正在永久停止当前实例，不再重连，也不接受新任务。

### 首次连接

首次连接同样依次经过 `CONNECTING`、`HYDRATING` 和 `ACTIVE`，与后文重连恢复流程的区别仅在于需要执行一次重复 `serverId` 检查：

1. 在任务队列中同步建立 Redis 命令连接并完成聊天频道和玩家列表通知频道的订阅，确认全部连接就绪后进入 `HYDRATING`。
2. 当前服务器进程第一次成功连接 Redis 时，先执行 `HGET` 检查自己的 `serverId` 是否已有 field。
3. 如果 field 已存在，在控制台输出醒目警告，并通过服务器线程向所有在线玩家广播红色加粗警告，提示 `serverName` 可能重复并可能导致玩家列表数据异常；告警后继续启动，不拒绝服务。
4. 向服务器线程提交玩家列表读取任务并同步等待当前本服玩家快照，`HSET`到远端redis，设置字段 TTL 并发布通知；已有同名 field 时直接覆盖。
5. 通过 `HGETALL` 读取所有有效远端快照，覆盖本地变量。
6. 更新内存中的完整远端视图，向服务器线程提交完整视图的 Add 数据包发送任务，然后进入 `ACTIVE` 并开始心跳。

同一进程在相同 Redis 数据集上使用相同 `serverId` 断线重连或者reload时，不执行步骤2-3的检查，因为断线前自己的旧记录可能还没有过期。

### 玩家加入或退出

- `ServerPlayConnectionEvents.JOIN`。
- `ServerPlayConnectionEvents.DISCONNECT`。

以上事件导致玩家集合变化时，执行完整 `HSET + HEXPIRE + PUBLISH` 流程，写入新快照、刷新 TTL 并发布通知。

注意加入退出事件可能发生在原版玩家列表真正变动之前。实现时应在调用 server.getPlayerList() 获得玩家列表后，主动检查并增删目标玩家。

变化写入使用事务保证订阅者收到通知前已经能读到新快照：

```text
MULTI
HSET CrossServerChat:PlayerList:Data <serverId> <ciphertext>
HEXPIRE CrossServerChat:PlayerList:Data 30 FIELDS 1 <serverId>
PUBLISH CrossServerChat:PlayerListUpdated <encrypted-notification>
EXEC
```

### 心跳

每 10 秒拉取远端当前serverId的记录，并生成当前服务器在线玩家按 UUID 排序的 列表，两者对比（仅比较UUID）：

- 玩家集合变化：执行完整 `HSET + HEXPIRE + PUBLISH` 流程。
- 玩家集合未变化：只刷新 field TTL，不重写快照，也不发布通知：

```text
HEXPIRE CrossServerChat:PlayerList:Data 30 FIELDS 1 <serverId>
```

这一步如果返回`[1]`的话代表成功，不用管。如果返回`[-2]`，说明条目可能不存在了，仍然执行前面的完整完整 `HSET + HEXPIRE + PUBLISH`流程

心跳三次为一个周期，前两次为普通心跳，第三次为全量更新。第三次心跳强制读取并写入本服包含最新延迟的完整快照、刷新 TTL、发布通知，并通过 `HGETALL` 拉取所有服务器的玩家列表到本地。

- 对于自己serverId的数据，第三次心跳强制执行完整写入；前两次做普通心跳的当前serverId记录检查和续期/更新
- 对于非自己serverId的数据，同步到本地哈希表变量中。如果发现本地有serverId记录已经在远端消失了，说明远端已经离线，删除本地的哈希表中对应的serverId变量，并向本服玩家发送通知

### 更新完成后，同步到玩家

每次本地哈希表数据更新完成后：

1. 合并哈希表中的所有玩家。
2. 排除当前本服所有真实在线 UUID（理论上应该不会有，因为本地维护的快照不包含自身服务器玩家）。
3. 按 UUID 字典序排序、去重；同一 UUID 出现在多个远端服时，按 `serverId` 字典序固定采用第一个快照
4. 与上一次已经发送给客户端的视图比较。
5. 仅发送新增、显示信息变化和删除数据包。

### 正常关闭

监听到服务器关闭事件时，启动正常关闭流程。正常关闭通知用于立即更新其他节点

```text
MULTI
HDEL CrossServerChat:PlayerList:Data <serverId>
PUBLISH CrossServerChat:PlayerListUpdated <encrypted-notification>
EXEC
```

正常关闭流程启动后，立刻 `STOPPING` 状态并封锁普通任务入队，停止心跳和重连计划，丢弃尚未开始的普通任务，并清空本地哈希表变量。接下来直接执行上述 `HDEL + PUBLISH` 事务，事务成功或明确失败后关闭 Redis 连接并进入 `STOPPED`；如果 Redis 已断线或事务失败，不再为了关闭而重连，遗留 field 由 TTL 清理。

注意，正常关闭不向玩家广播全体Remove消息，因为服务器都要关闭了，广播此消息无意义

### Redis 断线与重连

以下任一情况都视为当前 Redis 连接失效：

- Pub/Sub 订阅连接断开
- 玩家列表同步使用的 Redis 命令执行失败。

单个非法或无法解密的远端 field 属于数据错误，不属于连接断线。

从 `ACTIVE` 或 `HYDRATING` 进入 `DISCONNECTED` 时只执行一次以下操作：

1. 停止心跳，封锁普通任务入队，并丢弃队列中尚未开始的普通任务
2. 关闭当前订阅连接和命令连接。
3. 清空远端目录，并向服务器线程提交原远端视图的 Remove 数据包发送任务
4. 不执行 `HDEL`（因为断线了显然执行也不会成功）让 Redis 中本服旧 field 保留并依靠 TTL 过期
5. 按配置的重连间隔进入下一次 `CONNECTING`

在 `CONNECTING` 阶段连接或认证失败时直接进入 `DISCONNECTED` 并按间隔重试。

每次重连成功后，执行和冷启动一样的流程，但跳过步骤 2、3 的重复 `serverId` 检查和告警。

### Reload

reload 流程：

1. 向本服内所有玩家广播所有远端玩家的Remove包
2. reload 请求提交成功时，任务入口立即封锁普通任务和后续 reload 请求；reload 任务开始执行后，按照“正常关闭”一节完整关闭旧实例。
3. 旧实例`STOPPED`后，读取并校验新配置
4. 配置读取失败时保持 `STOPPED`，并向命令执行者提交 reload 失败消息。
5. 如果总开关或玩家列表同步开关关闭，保持 `STOPPED`。
6. 否则按照“首次连接”一节从 `CONNECTING` 开始执行完整冷启动，但跳过步骤 2、3 的重复 `serverId` 检查和告警。

### 异常退出和字段过期

异常退出后，对应 Hash field 会因字段 TTL 到期而自动消失，其他服务器全量检测发现列表错误的时候也会自动执行清理。

### 其它

- 玩家延迟数据的变化不触发更新流程（显而易见的，没必要）。仅在每次执行HSET前（比如30秒全量同步），从`player.connection.latency()`读取延迟信息，并跟玩家信息一起发布到redis
- 所有通知、心跳、全量更新、玩家加入退出、关闭和 reload 都封装为任务，由同一个单线程队列串行、同步执行。

## Minecraft Tab 列表更新

### 边界

只向本服真实玩家的网络连接发送原版 Player Info 数据包：

- 添加或更新：`ClientboundPlayerInfoUpdatePacket`。
- 删除：`ClientboundPlayerInfoRemovePacket`。

禁止把远端条目加入 `MinecraftServer.getPlayerList()`，也禁止构造假的 `ServerPlayer`。因此服务端在线人数、命令、实体和存档仍然只包含本服玩家。

### 条目字段

第一版建议：

| 字段                           | 值                                     |
| ------------------------------ | -------------------------------------- |
| Profile UUID                   | 远端玩家真实 UUID                      |
| Profile name                   | 远端玩家名称                           |
| Listed                         | `true`                               |
| Latency                        | 远端玩家延迟值                         |
| Game mode                      | `SURVIVAL`，仅作为数据包占位值       |
| Display name（放在配置文件中） | 按配置文件，支持minimessage            |
| Show hat                       | `true`                               |
| List order                     | `-1`，使远端条目排在默认本服条目之后 |
| Chat session                   | `null`，不伪造远端签名聊天会话       |

新增新玩家数据包包含这些 Action：

```java
EnumSet.of(
  Action.ADD_PLAYER,
  Action.UPDATE_GAME_MODE,
  Action.UPDATE_LISTED,
  Action.UPDATE_DISPLAY_NAME,
  Action.UPDATE_LATENCY,
  Action.UPDATE_LIST_ORDER,
  Action.UPDATE_HAT
)
```

更新玩家数据包按实际变化选择 Action。玩家延迟改变时：

```Java
EnumSet.of(
  Action.UPDATE_LATENCY,
)
```

显示服务器或显示文本变化时使用 `UPDATE_DISPLAY_NAME`；两项同时变化时同时携带这两个 Action。由于 Profile 名称没有独立更新 Action，同一 UUID 的名称变化使用先 Remove、再按新增玩家 Add 的方式更新。

删除玩家时发送：

```java
new ClientboundPlayerInfoRemovePacket(List.of(uuid))
```

注意发送前检查，永远不对当前本服在线 UUID 发送远端 REMOVE

本服玩家刚加入时，只向该玩家发送当前完整远端视图；远端状态变化时再向所有本服玩家广播差异。

### Minecraft 26.2 Accessor

Minecraft 26.2 的 `ClientboundPlayerInfoUpdatePacket.Entry` 构造器公开，但数据包没有接受任意 Entry 集合的公开构造器。需要一个最小 Mixin Accessor 修改构造后数据包的 `entries` 字段：

```java
@Mixin(ClientboundPlayerInfoUpdatePacket.class)
public interface PlayerInfoPacketAccessor {
    @Mutable
    @Accessor("entries")
    void crossServerChat$setEntries(
            List<ClientboundPlayerInfoUpdatePacket.Entry> entries
    );
}
```

创建方式：

```java
var packet = new ClientboundPlayerInfoUpdatePacket(
        actions,
        List.<ServerPlayer>of()
);
((PlayerInfoPacketAccessor) (Object) packet)
        .crossServerChat$setEntries(entries);
```

Mixin 配置只声明该 Accessor，不应注入或覆盖原版玩家列表逻辑。

## 线程模型

- 玩家列表同步管理器拥有一个贯穿服务器进程生命周期的专用单线程任务队列，同一时刻最多管理一个同步实例。除 Minecraft 线程边界操作外，所有逻辑都在该队列中串行、同步执行；Redis 命令使用同步调用。
- Fabric 事件、Redis Pub/Sub 通知、断线事件和定时器只负责尝试向队列提交任务，不直接修改任何玩家列表同步状态。
- Minecraft 玩家列表只能在服务器线程读取。任务队列需要读取时，向服务器线程提交读取任务并阻塞等待结果；服务器线程只执行本地内存读取，不等待 Redis。
- Player Info 数据包只能由服务器线程发送。任务队列提交单向发送任务后立即继续，不等待结果，也不设置完成回调。
- `STOPPED`、`DISCONNECTED` 和 `STOPPING` 状态会在任务入口封锁不应接受的普通任务，并清除队列中尚未执行的普通任务。重连和关闭等控制任务仍可按对应状态进入队列。
- 不设置生命周期代次或连接代次，也不在任务中进行代次检查。关闭、reload 和重连的先后关系完全由队列封锁和串行执行保证。

## 协议与加密

玩家列表同步状态不能塞进现有 `RelayMessage.text`，也不应添加一个携带 JSON 字符串的聊天 `MessageType`。它应有独立的消息类型和验证逻辑。

为避免破坏已有聊天线协议，保持 `MessageCodec` 当前聊天帧版本、HKDF info 和 AAD 不变。增加独立 `PlayerListSyncCodec`。

## 配置设计

建议在下一配置版本增加最小配置，暂时不把心跳、TTL 和更新周期暴露给用户：

```yaml
player-list-sync:
  enabled: true
  displayFormat: "%player% <green>[%server%]</green>"
```

- 默认开启，但是总开关关闭的时候，功能仍然保持关闭
- `displayFormat` 支持 `%server%`、`%player%`，可复用项目现有 MiniMessage 解析方式。
- reload 时先按正常关闭流程完全停止旧实例，完全关闭后，才读取并应用新配置，从零启动新实例并执行完整更新。
