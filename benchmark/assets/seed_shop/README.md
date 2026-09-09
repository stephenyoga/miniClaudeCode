# seed_shop —— 演示用多模块商城项目

一个不含外部框架、可用 `javac` 整编的小型 Java 项目，供编码助手评测使用。

## 模块

```
src/com/shop/
├── model/       领域模型：Customer / Product / Order / OrderItem
├── repository/  内存存储：CustomerRepository / ProductRepository / OrderRepository / InventoryRepository
├── service/     业务层：OrderService(下单/取消) / InventoryService(库存) / PricingService(定价)
│                PaymentService(支付) / ReportService(报表)
├── util/        工具：IdGenerator / PriceFormatter / CsvExporter / Validation
└── web/         伪路由层：OrderController / ProductController / ReportController / App(演示入口)
```

## 构建与运行

Windows: `build.cmd`；Unix: `bash build.sh`。产物在 `out/`，演示入口 `com.shop.web.App`。

## 业务要点

- 库存与商品目录分离：库存是单独数据源，下单走 `InventoryService.reserve` 扣减。
- 订单状态：CREATED → PAID → SHIPPED；CREATED/PAID 可取消为 CANCELLED。
- 收入口径：仅 PAID / SHIPPED 订单计入报表。
- 定价：`PricingService.charge` 是订单与控制器共用的最终计价入口，VIP 打九折。

`data/` 目录为数据样例（仅供阅读，程序启动时使用内置内存种子数据）。
