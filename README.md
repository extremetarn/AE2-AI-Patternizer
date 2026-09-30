# AE2-AI-Patternizer

> 让 AI 替你写 AE 样板。Give your ME network an AI pattern writer.

**AI Patternizer** 是 [Applied Energistics 2](https://github.com/AppliedEnergistics/Applied-Energistics-2) 的附属 mod：玩家自带 LLM API（OpenAI 兼容协议，DeepSeek / Kimi / Ollama 等均可），在游戏里用自然语言编写、批量补齐 ME 样板。

## 为什么做这个

纯程序工具（如 ae-all-pattern）有两个填不上的坑：

- **读不懂人话**：整合包作者写在 JEI/EMI 信息页里的提示（"注魔水晶不会被消耗"、"只能在末地使用"）是自然语言，程序无法判断，但它们决定了样板该怎么写；
- **分不清离谱**：同一产物多条配方路线时，程序会选出"铁锭→合成铁镐→烧成铁粒"这种算法合法但荒谬的路线。

这个 mod 的答案：**算法扛树，AI 扛判断**。配方树展开、缺口比对、数量折算全部用确定性算法；自然语言理解、催化剂标注、路线合理性评审交给 LLM，且每个 AI 决策都附依据、可被玩家推翻。

## 功能

- **单板模式**：说一句话出一张样板（合成 / 处理 / 切石 / 锻造）；
- **假合成**：催化剂（不消耗 / 仅耗耐久）专用写法——返还式、预置式、耐久折批式；
- **整线模式**：给个目标（如 ATM 之星），自动补齐 ME 网络缺失的所有样板 + 按机器分组的放置建议；
- **JEI / EMI 双兼容**：读取配方分类→机器映射与作者信息页，软依赖、可降级；
- **BYOK**：API Key 只存客户端，服务端零密钥。

## 状态

🚧 开发中（dev 分支）：M1 工程骨架与编码链路、M2 LLM 接入全链路已完成并通过集成测试。

当前目标：Minecraft 1.20.1 · Forge 47.4 · AE2 15.x · Java 17（1.21.1 NeoForge 版后续移植）

## License

[MIT](LICENSE)
