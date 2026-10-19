# Getting Started

### Reference Documentation
For further reference, please consider the following sections:

* [Official Apache Maven documentation](https://maven.apache.org/guides/index.html)
* [Spring Boot Maven Plugin Reference Guide](https://docs.spring.io/spring-boot/4.1.1/maven-plugin)
* [Create an OCI image](https://docs.spring.io/spring-boot/4.1.1/maven-plugin/build-image.html)
* [Spring Web](https://docs.spring.io/spring-boot/4.1.1/reference/web/servlet.html)

### Guides
The following guides illustrate how to use some features concretely:

* [Building a RESTful Web Service](https://spring.io/guides/gs/rest-service/)
* [Serving Web Content with Spring MVC](https://spring.io/guides/gs/serving-web-content/)
* [Building REST services with Spring](https://spring.io/guides/tutorials/rest/)

### Maven Parent overrides

Due to Maven's design, elements are inherited from the parent POM to the project POM.
While most of the inheritance is fine, it also inherits unwanted elements like `<license>` and `<developers>` from the parent.
To prevent this, the project POM contains empty overrides for these elements.
If you manually switch to a different parent and actually want the inheritance, you need to remove those overrides.


## 第80课：自动接口文档

新增springdoc-openapi 3.1.1；访问与契约验收见 [接口文档指南](docs/API_DOCUMENTATION.md)。开发环境设置API_DOCS_ENABLED=true后重启，通过http://localhost:8080/docs.html访问。默认关闭，仅允许本机。

## 第81课：一键隔离回归验收

在后端目录执行 `python3 -B scripts/test_backend.py --check` 检查环境，执行 `python3 -B scripts/test_backend.py --offline` 运行完整隔离测试。入口、结果和排查说明见 [TESTING.md](TESTING.md)。

## 第82课：快速验收与CI配置

无需启动MySQL/Redis/RabbitMQ的快速检查：`python3 -B scripts/test_backend.py --suite quick`。分层范围与工作流放置位置见 [CI.md](CI.md)。
