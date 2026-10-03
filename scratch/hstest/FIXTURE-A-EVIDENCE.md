# 夹具 A：`hasUnmatchedChild` 死锁的现场证据（已验证）

## 夹具

```
v1: build() { int a=1,b=2; Time.run(10, () -> { tag(b); Time.run(5, () -> use(a)); }); }
v2: build() { int a=1,b=2; Time.run(10, () -> { tag(b); Time.run(5, () -> use(a,b)); }); }
```

两版**类名相同**（包私有 `FixtureA`，类名不必等于文件名；`align` 要求新旧类名一致）。
JDK 8 实测描述符：

```
v1: lambda$build$1(II)V   lambda$null$0(I)V      ← 中层两侧相同
v2: lambda$build$1(II)V   lambda$null$0(II)V     ← 只有叶子变
```

## align 输出（`DEBUG=true`，JDK 8 javac 产物）

```
SKIP(parent has unmatched child) parent=lambda$build$1 child=lambda$null$0 childMatched=false   ×5
NO-CANDIDATE lambda$null$0 hash=351230092063535765 sem=351230092063535765 旧侧候选数=0
BLOCKED parent=lambda$build$1 by child=lambda$null$0(childMatched=false, 旧侧候选数=0)
        parentHash=-5968402878468211685 parentSem=3497223746808501572 parentCand=1
```

## 读法

| 行 | 含义 |
|---|---|
| `NO-CANDIDATE ... 旧侧候选数=0` | 叶子因**描述符变化**（`(I)`→`(II)`）确实找不到任何候选 |
| `BLOCKED parent=... by child=...` | 中层被未落定的子挡住 |
| **`parentCand=1`** | **中层本身有 1 个结构上可用的旧候选，却因 `hasUnmatchedChild` 拿不到** |

## 最终方法表（对齐后）

```
lambda$build$0(II)V   ← 新中层，fresh name（旧名没保住）
lambda$null$0(II)V    ← 新叶子，fresh name
lambda$build$1(II)V   ← 旧名被幽灵化
lambda$null$0(I)V     ← 旧叶子名字被幽灵化
```

**结论**：旧中层 `lambda$build$1` 被幽灵化、新中层被迫拿 fresh name。
死锁假设成立，且**不是"中层无候选"**（它有 1 个），而是被未落定的子挡住。

## 待办

修复方案 `settled`：全部趟收敛后，把"可改名且仍未配对"的方法标记为已定稿，
让 `hasUnmatchedChild` 忽略它们，再重跑三趟（`do { 标记; 跑 } while (有进展)`）。

按评审要求，断言以 **KNOWN 形式**（钉住当前"整链幽灵化"行为）先提交，
`settled` 修好后 KNOWN 会自己变红，届时替换为期望行为版并更新 `expected-count.txt`。

---

## 更正：新叶子**没有**拿 fresh name（评审第 1 点）

最终表里的 `lambda$null$0(II)V` **就是 javac 给的原名**。阶段二的冲突判据是
`usedOldNames.contains(name) || oldNameDescSet.contains(name+desc)`，两者都不命中，
于是保持原名。它与幽灵 `lambda$null$0(I)V` **同名不同描述符**，JVM 合法。

后果：**"中层 indy 指向新叶子的 fresh name"这条不适用于本夹具**，
链条闭合在现状下可能已经是绿的，**不能用作红指示器**，只宜作护栏断言。

真正的红指示器只有三条（一律按 `name+desc` 比较，不只比名字）：
1. 旧中层名在最终类里是**活方法**（不含 `onOrphanInvoked`）而非幽灵；
2. 新中层**沿用了**该旧名；
3. 该名的 shape 与旧类一致。

## `parentCand=1` 数的是什么（评审第 2 点）

`countCompatCandidates` 只数**签名兼容 + shape 相等**的未匹配旧候选，
**不含** `sameSemantics`、也不含 `calleesPairTo`。它证明的是"**存在结构上可用的候选**"。

**"解除阻塞后一定会被接受"尚未验证** —— 要等 `settled` 做完才算，现在不作为结论。

## V2→V3 稳定性实测（评审第 3 点，已跑）

把同一份 V2 再对齐一次（等价于"源码不变又保存一次"）：

```
第一轮: lambda$build$0(II)V  lambda$null$0(II)V  lambda$build$1(II)V  lambda$null$0(I)V
第二轮: lambda$build$0(II)V  lambda$null$0(II)V  lambda$build$1(II)V  lambda$null$0(I)V
两轮方法键集合一致 = true
```

**结果：没有显现出名字键索引的问题。**

需要谨慎解读：这只说明该场景下**可观测输出稳定**。
评审推演的是 `oldNameIndex` 被幽灵覆盖、`computeSemanticHashes` 跳过幽灵、
导致中层语义指纹失配——这条链若成立，**影响的是中层能否走 Step 1**，
本夹具里中层本来就走 A/B 趟（描述符/指纹已变），所以**可能被掩盖了**。
要真正验证，需要一个"中层本应走 Step 1"的夹具。
因此本结果**不构成"名字键索引没问题"的证明**，只排除了一种最直接的失稳表现。
