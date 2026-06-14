# dropsweeper

> 智能掉落物清扫系统 — 帮你的 Minecraft 服务器自动清理堆积的掉落物
> 
> 带着友好的「扫地姬」陪伴，从此告别满地的圆石、沙子、箭矢。ฅ^•ﻌ•^ฅ

[![Minecraft](https://img.shields.io/badge/Minecraft-26.1.2-green.svg)](https://www.minecraft.net/)
[![Fabric](https://img.shields.io/badge/Loader-Fabric-blueviolet.svg)](https://fabricmc.net/)
[![Java](https://img.shields.io/badge/JDK-25-orange.svg)](https://www.azul.com/downloads/)
[![License](https://img.shields.io/badge/License-CC0-lightgrey.svg)](#license)

---

## 这是什么？

`dropsweeper` 是一个 Fabric 服务端模组，自动把地上没人捡的掉落物收集到**垃圾箱**里（每格 54 槽的虚拟大箱子），让你：

- 🧹 **自动清扫**：可设周期（默认 10 分钟一次），到点自动执行
- 📦 **多垃圾箱**：默认 1 个，可配置 N 个，支持 FIFO 跨箱淘汰
- 🚫 **白/黑名单**：白名单物品永不清理，黑名单物品直接清理不入箱
- 🎯 **额外实体清理**：箭矢、经验球等也能一起清
- ⚠️ **过载警告**：某区块物品堆积过多时通知 OP
- 💬 **友好提示**：清扫前倒计时（ActionBar/聊天），完成时附带可点击的垃圾箱链接
- 🔧 **配置热重载**：改完配置不用重启服务器
- 🔐 **权限可配**：谁可以开垃圾箱、谁可以清扫都能分级

---

## 安装

1. 安装 [Fabric Loader 0.19.3+](https://fabricmc.net/) 和 [Fabric API 0.151.0+](https://modrinth.com/mod/fabric-api)
2. 把 `dropsweeper-1.x.x.jar` 放到服务器的 `mods/` 目录
3. 启动一次服务器，会自动生成默认配置 `config/dropsweeper.json`
4. 重启服务器，搞定 ✨

> 客户端**不需要**安装，纯服务端模组。

---

## 命令一览

所有命令都以 `/dropsweeper` 开头。

### 清扫

| 命令 | 权限 | 说明 |
|---|---|---|
| `/dropsweeper items` | OP | 清扫当前维度 |
| `/dropsweeper items all` | OP | 清扫所有维度 |
| `/dropsweeper items <世界ID>` | OP | 清扫指定维度（Tab 补全） |

### 打开垃圾箱

| 命令 | 权限 | 说明 |
|---|---|---|
| `/dropsweeper dustbin` | 所有人 | 打开垃圾箱 0 |
| `/dropsweeper dustbin <编号>` | 所有人 | 打开指定编号的垃圾箱 |

> 清扫完成消息里会自动列出非空垃圾箱，**点击即可打开**。

### 白/黑名单管理

```
/dropsweeper whitelist add <物品ID>     # 加入白名单（永不清理）
/dropsweeper whitelist remove <物品ID>  # 移出白名单
/dropsweeper whitelist list             # 查看白名单

/dropsweeper blacklist add <物品ID>     # 加入黑名单（清理但不入箱）
/dropsweeper blacklist remove <物品ID>  # 移出黑名单
/dropsweeper blacklist list             # 查看黑名单
```

> 支持 `modid:*` 通配符，例如 `minecraft:*` 表示原版所有物品。

### 配置

```
/dropsweeper config reload    # 热重载 config/dropsweeper.json
```

---

## 配置文件

位置：`config/dropsweeper.json`（首次启动自动生成）。

### 常用字段

```json
{
  "sweepIntervalSeconds": 600,           // 自动清扫周期（秒），0 = 禁用
  "dustbinCount": 1,                     // 垃圾箱数量（每箱 54 槽）
  "itemWhitelist": [                     // 白名单：永不清理
    "minecraft:nether_star",
    "minecraft:heavy_core"
  ],
  "itemBlacklist": [                     // 黑名单：清理但不入箱
    "minecraft:cobblestone",
    "minecraft:sand",
    "minecraft:gravel"
  ],
  "extraEntityTypes": [                  // 额外清理的实体
    "minecraft:arrow",
    "minecraft:spectral_arrow",
    "minecraft:experience_orb"
  ],
  "itemOverloadThreshold": 640,          // 单 chunk 物品堆积警告阈值，0 = 禁用
  "permissionLevelDustbin": 0,           // 开垃圾箱的最低权限（0=任何玩家）
  "permissionLevelClean": 2,             // 手动清扫的最低权限
  "announcementChannel": {               // 提示通道
    "long": "actionbar",                 // 长倒计时（默认 120/60/30 秒）
    "short": "chat",                     // 短倒计时（≤3 秒）
    "complete": "chat"                   // 清扫完成
  }
}
```

### 权限等级对照

| 等级 | MC 26.1.2 对应 |
|---|---|
| 0 | 任何玩家（无权限要求） |
| 1 | 游戏管理员 |
| 2 | 协管 |
| 3 | 管理员 |
| 4 | 服主 |

---

## 提示通道说明

- **ActionBar**：屏幕中央上方的小字，60 tick（3 秒）后自动消失，**不刷屏**
- **Chat**：聊天栏醒目消息，**不会自动消失**

默认行为：长倒计时用 ActionBar 不打扰，短倒计时和完成消息用 Chat 让你看得见。

---

## 垃圾箱能装多少？

- 每个垃圾箱 54 槽（6 行 × 9 列，等价于一个大箱子）
- 默认 1 个 → 54 槽
- 设 `dustbinCount: 8` → 8 × 54 = 432 槽
- 满了之后**最旧**的物品会被新物品覆盖（FIFO）

---

## 数据存储

- 垃圾箱物品存储在存档里：`world/data/dimensions/minecraft/overworld/data/dropsweeper/dustbin.dat`
- 自动保存，不需要手动操作
- 减少垃圾箱数量 → 会**直接截断**多余的箱子，可能丢失非空箱子里的物品
- 重新增加垃圾箱数量 → 数据保留

---

## 常见问题

**Q：垃圾箱在哪里？怎么打开？**  
A：执行 `/dropsweeper dustbin`（默认任何玩家可用），或者看清扫完成消息里的可点击链接。

**Q：怎么添加自定义物品到白/黑名单？**  
A：用 `/dropsweeper whitelist add <物品ID>` 命令，物品 ID 格式为 `minecraft:diamond` 这种。也可以直接编辑 `config/dropsweeper.json` 后 `/dropsweeper config reload`。

**Q：改完配置必须重启服务器吗？**  
A：不用。执行 `/dropsweeper config reload` 立即生效。

**Q：客户端需要装吗？**  
A：不需要，纯服务端模组。

**Q：会和其它清扫模组冲突吗？**  
A：会。如果同时有两个模组清扫掉落物，可能重复清扫。建议二选一。

---

## 致谢

本项目借鉴了以下优秀开源项目：

- [Sweeper-Maid](https://github.com/Hexagram111/Sweeper-Maid-dev-Forge-1.20.1) — 命令结构与提示分层的灵感来源
- [Atom-Sweep](https://github.com/CommitteeOfNineteen/Atom-Sweep-multi-1.21.1) — 多平台项目结构参考

---

## License

CC0 1.0 Universal — 你可以自由学习、修改、再发布，包括商用。
详见 [LICENSE](LICENSE) 文件。
