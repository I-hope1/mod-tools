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
