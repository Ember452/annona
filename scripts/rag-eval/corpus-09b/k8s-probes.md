# Kubernetes 探针

## 三种探针的分工

liveness 探针失败会重启容器；readiness 探针失败只把 Pod 摘出 Service 端点，不触发重启。
startup 探针成功之前屏蔽另外两种，为慢启动应用保留初始化窗口。

## 配置要点

initialDelaySeconds 是首次探测前的等待时间；failureThreshold 决定连续失败多少次才判定失败。
探测方式有 httpGet、tcpSocket、exec 三种，就绪判定应尽量探测真实依赖而不是进程存活。
