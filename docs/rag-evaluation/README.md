# RAG 检索质量评估

这个目录是知识库链路检索质量的评估材料。**结论请读 [report.md](report.md)**，本文只讲怎么用这个目录、怎么复跑、以及引用数据时的几个注意点。

---

## 目录构成

| 文件 | 性质 |
|---|---|
| [report.md](report.md) | **评估报告正文**——结论、数据、失败案例、已知局限。要读的就是它 |
| [questions.tsv](questions.tsv) | 金标准问题集，25 题。被测试类读取，**不是给人读的** |
| [corpus/](corpus/) | 评估语料，三份公开中文公文 |
| [results/](results/) | 原始结果，由测试类**自动生成**（文件头注明请勿手工编辑） |

`report.md` 里的每张表都是 `results/` 的子集，但 `results/retrieval-results.md` 独有一份**相似度得分分布**（正文没有引用它）——它能说明「答得出的题」与「答不出的题」在得分上是否可分。

---

## 语料为什么是这三份

`corpus/` 下三份公文是**刻意挑的**，分别代表三种文档结构形态：

| 文档 | 形态 |
|---|---|
| 01 事业单位人事管理条例 | 结构良好（十章四十四条，标题层级完整） |
| 02 银川市市监局请休假及考勤管理制度 | 局部结构失衡（数字密集，第十五条单条 1222 字） |
| 03 银川市市监局培训教育费和学历教育学费报销办法 | 整体结构扁平（无任何小节标题） |

后两种是真实公文里最常见的形态，也是切分最容易出问题的地方。**不要替换语料**——报告里的所有结论都建立在「这三种形态」之上，换了语料结论就失效了。

---

## 怎么复跑

两个评估类受环境变量 **`RAG_EVAL`** 门控（`@EnabledIfEnvironmentVariable`），**默认跳过**——它们要联网调 embedding 与大模型、耗时数分钟、要花钱。

```bash
cd doc-qa-api

# 金标准自检 + 切分探查（不联网，默认就会跑，留在 mvn test 里）
JAVA_HOME="<你的JDK17路径>" mvn test -Dtest='QuestionSetSelfCheckTests,ChunkingProbeTests'

# 检索层评估（约 40 秒）
RAG_EVAL=true JAVA_HOME="<你的JDK17路径>" \
  DASHSCOPE_API_KEY=<你的 Key> mvn test -Dtest=RagRetrievalEvaluationTests

# 端到端评估（约 6 分钟）
RAG_EVAL=true JAVA_HOME="<你的JDK17路径>" \
  DASHSCOPE_API_KEY=<你的 Key> mvn test -Dtest=EndToEndEvaluationTests
```

`JAVA_HOME` 必须指向 JDK 17，原因见 `../pitfalls.md` §1。

结果的落点：

- `results/retrieval-results.md` ← `RagRetrievalEvaluationTests`
- `results/end-to-end-results.md` ← `EndToEndEvaluationTests`

### ⚠️ 跑之前必须确认 `t_vector_store` 是空的

两个评估类都会**先断言该表为空**，跑完再 `TRUNCATE`。

原因：评估用的检索链路与线上**逐字相同**——同样**不带 metadata filter**。表里混进任何非本轮语料都会直接污染结果。

所以跑之前请确认没有正在使用的知识库文件（或者直接 `TRUNCATE TABLE t_vector_store`）。

---

## ⚠️ 引用数据时的三个注意点

### 1. 用「人工复核」口径，不要用「自动判分」口径

`report.md` §5.1 那张表有两列。**裸模型组的 63.6% 是人工复核出来的数**，而 `results/end-to-end-results.md` 里**只有自动判分口径（77.3%）**。

也就是说：**63.6% 这个数无法从原始结果文件复现**，它只存在于报告正文里。

差异来自自动判分对裸模型组**系统性偏高**——会把「罗列各地标准、碰巧含期望数字」的回答误判为正确（那实际是没作答）。RAG 两组两列逐题一致，没有这个问题。

**引用时请用人工复核口径。**

### 2. 百分比的分母很小，别当精确刻度

有金标准的是 **22 题**（25 题里 3 题是 irrelevant 类，正确答案是拒答、无金标准）。

所以**一题之差 = 4.5 个百分点**。报告里「3→10 提升 4.5 个点」这类说法，实际含义是「多救回 1 题」。

### 3. `questions.tsv` 的 `keys` 列是刻意的设计，不要改

`keys` 列存的是 **key phrase 文本**，不是 chunk ID。

原因：不同切分策略的块边界不同，chunk ID **无法跨策略比较**。用文本匹配才能让五种策略跑在同一套金标准上。

⚠️ 配套的约束：**每条 key phrase 必须完整落在某一个块内**。若它正好被切点劈成两半，那么无论检索多好都匹配不上，该题会永远计为未命中——而**从结果表上完全看不出异常**。污染出来的是一份假数据。

这就是 `QuestionSetSelfCheckTests` 存在的意义：它对**每个切分策略分别**校验这一点，且不联网、不启动 Spring，默认留在 `mvn test` 里跑。**改动问题的 key phrase 后务必确认它仍然通过。**

---

## 相关代码

| 类 | 作用 |
|---|---|
| `QuestionSetSelfCheckTests` | 金标准自检（默认跑） |
| `ChunkingProbeTests` | 切分粒度探查（默认跑） |
| `RagRetrievalEvaluationTests` | 检索层评估（`RAG_EVAL` 门控） |
| `EndToEndEvaluationTests` | 端到端评估（`RAG_EVAL` 门控） |

路径：`../../doc-qa-api/src/test/java/io/github/renhaowan/docqa/rageval/`
