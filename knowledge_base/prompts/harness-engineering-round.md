# Harness Engineering Round

当用户说 "harness engineering"、"整理 harness" 或 "/harness" 时执行此检查清单。

## 概念

Harness Engineering = 维护项目的 AI Agent 工作环境（CLAUDE.md、settings、knowledge_base、memory），
使 AI 能高效理解项目、正确执行操作、避免重复错误。

核心理念来自 `knowledge_base/methodology/harness-engineering.md`：
> 代码仓库是系统记录。Agent 无法发现的知识等于不存在。

## 检查清单

### 1. CLAUDE.md 审查

- [ ] 末尾是否有遗留的修改记录/commit message？（应删除——git history 才是真正的记录）
- [ ] 项目定位是否准确反映了当前阶段？（Fab MES 内核？M1-M4 路线图？）
- [ ] 技术栈版本号是否仍然正确？
- [ ] 架构规则是否仍然准确？（依赖方向铁律、FP 约定、Saga 规则）
- [ ] 有没有近期踩过的坑需要加入？（新 TDD 陷阱、新编译问题、新 Protobuf 注意事项）
- [ ] Directory Map 是否包含所有关键目录？（views/、public/、protobuf/ 等）
- [ ] 是否引用了 knowledge_base/ 中的最新文档？
- [ ] 总行数是否在 ~80 行以内？（超过 100 行需要精简）

### 2. Settings 审查

- [ ] `.claude/settings.local.json` 是否有必要的 Bash 权限？
  推荐: `Bash(git *)`, `Bash(sbt *)`
- [ ] 是否有不必要的过多权限？（最小权限原则）
- [ ] 是否需要添加 hooks？（如 pre-commit 自动检查）

### 3. Knowledge Base 审查

- [ ] 方法与事实是否有中英文同步版本？
  - `methodology/*.md` 和 `methodology/*-zh.md` 是否内容对应？
  - `architecture/*.md` 是否需要中文版本？
- [ ] `artifacts/` 中的模板是否与当前代码模式一致？
  - 最近新增的 artifact 类型是否有对应模板？
  - 已废弃的 artifact 类型是否已删除模板？
- [ ] 跨文档链接是否仍然有效？（引用路径是否正确）

### 4. Memory 审查

- [ ] Memory 文件是否仍然准确？（参考路径、外部系统 URL）
- [ ] 有没有过期的 memory 需要更新或删除？
- [ ] MEMORY.md 索引是否与实际 memory 文件一致？

### 5. 项目对齐检查

- [ ] 首页（`index.scala.html` + `indexZh.scala.html`）的叙事是否与 CLAUDE.md 的定位一致？
- [ ] Demo 页面（showcase、projection-showcase、ddd-guide）的 Fab 场景标签是否准确？
- [ ] GitHub Issues/Discussions 链接是否指向正确的仓库？

## 修复原则

1. **Codebase is the system of record.** 如果 CLAUDE.md 和代码不一致，改 CLAUDE.md。
2. **Progressive disclosure.** CLAUDE.md 是指针，不是百科全书。细节放 knowledge_base/。
3. **Mechanical enforcement.** 能自动检查的规则优先写进 CI，而不是写进文档祈祷 Agent 遵守。
4. **Garbage-collect.** 坏模式尽早清理。修改记录、commit message 不从 git 推导而写在文档里就是垃圾。

## 验证

```bash
sbt compile   # 确保所有改动编译通过
```

完成后用一句话总结：改了哪些文件，为什么。
