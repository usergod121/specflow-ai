# inventory（端到端验收用的目标项目）

纯 Java，**零外部依赖**：编译只要 `javac`，容器里不用下任何东西。这样"跑不通"的原因
就只可能是引擎自己的毛病，而不是网络慢或者依赖装不上。

## 编译

    javac -d out/main src/main/java/com/demo/*.java

## 跑

    java -cp out/main com.demo.InventoryApp

## 里面那个 bug

`InventoryStore.reserve` 在库存不足时**照样扣、照样返回 true**，会把 `on_hand` 扣成负数。
需求（验收时用）：

> 修掉库存会被扣成负数的问题：reserve 在库存不足时必须返回 false，而且不能把 on_hand 扣成负数。
> 现有方法签名与调用方不要动。

修好之后 `java -cp out/main com.demo.InventoryApp` 应该打印：

    reserve(5) = false
    onHand(A) = 3
