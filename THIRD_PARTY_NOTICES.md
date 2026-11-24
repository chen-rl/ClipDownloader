# 第三方声明（Third-Party Notices）

本项目部分源码移植自下列第三方项目，其著作权归原作者所有，并按其原始许可证提供。

## Evil0ctal/Douyin_TikTok_Download_API

- 项目：https://github.com/Evil0ctal/Douyin_TikTok_Download_API
- 版权：Copyright 2021-2026 Evil0ctal and contributors
- 许可证：Apache License, Version 2.0（全文见 `licenses/Apache-2.0`）

### 涉及文件

下列文件由该项目的 `src/dtk/signing/native/` 下同名 Python 模块移植为 Kotlin，
算法逻辑保持一致，仅做了语言转换与命名/注释调整：

| 本项目文件 | 来源 | 说明 |
|------------|------|------|
| `app/src/main/java/com/clipdownloader/download/sign/Sm3.kt` | `sm3.py` | 国密 SM3 哈希 |
| `app/src/main/java/com/clipdownloader/download/sign/ABogus.kt` | `abogus.py` | 抖音网页端请求参数 a_bogus |
| `app/src/main/java/com/clipdownloader/download/sign/DouyinWebSign.kt` | `websign.py` | 抖音 x-secsdk-web-signature |

`app/src/test/resources/douyin_signing_golden.json` 中的黄金向量由上述 Python 模块生成
（文件内 `_source` 字段记录了生成时参考实现的提交号），用于校验 Kotlin 移植与参考实现逐位一致。

### 变更说明

- Python → Kotlin 的语言转换；
- RC4 伪随机生成阶段的 `j` 重置逻辑以显式赋值表达（语义与参考实现一致）；
- `DouyinWebSign.Result` 以数据类返回查询串与签名（参考实现返回元组）。

---

除上述文件外，本项目其余源码的著作权与许可证状态以仓库所有者的声明为准。
