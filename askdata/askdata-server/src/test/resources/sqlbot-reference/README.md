# SQLBot 参考实现（parity 基线）

本目录存放 askdata 需要「逐条等价」比对的 SQLBot 源码片段，作为测试夹具固定下来，
使 `AuthWhitelistParityTest` 在任意机器上都能真实执行，而不是依赖本机是否克隆了 SQLBot 仓库。

- `whitelist.py` —— 来源：`dataease/SQLBot` 仓库 `backend/common/utils/whitelist.py`
  （拉取于 2026-11-02，main 分支）。内容保持原样，不做任何改写；
  测试直接抽取其中的 `wlist` 与 `class WhitelistChecker` 代码块交给 python3 执行。

更新方式：重新拉取该文件覆盖本文件，并同步核对 `AuthWhitelist.DEFAULT_PATHS`。
如需临时指向本机克隆的源码，用 `-Dsqlbot.root=<SQLBot backend 目录>` 覆盖。
