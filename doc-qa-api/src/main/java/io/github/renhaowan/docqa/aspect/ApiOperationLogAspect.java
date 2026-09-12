
package io.github.renhaowan.docqa.aspect;

import io.github.renhaowan.docqa.utils.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Aspect
@Component
@Slf4j
public class ApiOperationLogAspect {

    /** 以自定义 @ApiOperationLog 注解为切点，凡是添加 @ApiOperationLog 的方法，都会执行环绕中的代码 */
    @Pointcut("@annotation(io.github.renhaowan.docqa.aspect.ApiOperationLog)")
    public void apiOperationLog() {}

    /**
     * 环绕
     * @param joinPoint
     * @return
     * @throws Throwable
     */
    @Around("apiOperationLog()")
    public Object doAround(ProceedingJoinPoint joinPoint) throws Throwable {
        // 请求开始时间
        long startTime = System.currentTimeMillis();

        // 获取被请求的类和方法
        String className = joinPoint.getTarget().getClass().getSimpleName();
        String methodName = joinPoint.getSignature().getName();

        // 请求入参
        Object[] args = joinPoint.getArgs();
        // 入参转 JSON 字符串（含 MultipartFile 脱敏与序列化异常兜底）
        String argsJsonStr = safeArgsToJson(args);

        // 功能描述信息
        String description = getApiOperationLogDescription(joinPoint);

        // 打印请求相关参数
        log.info("====== 请求开始: [{}], 入参: {}, 请求类: {}, 请求方法: {} =================================== ",
                description, argsJsonStr, className, methodName);

        // 执行切点方法
        Object result = joinPoint.proceed();

        // 执行耗时
        long executionTime = System.currentTimeMillis() - startTime;

        // 打印出参等相关信息
        log.info("====== 请求结束: [{}], 耗时: {}ms, 出参: {} =================================== ",
                description, executionTime, safeToJson(result));

        return result;
    }

    /**
     * 获取注解的描述信息
     * @param joinPoint
     * @return
     */
    private String getApiOperationLogDescription(ProceedingJoinPoint joinPoint) {
        // 1. 从 ProceedingJoinPoint 获取 MethodSignature
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();

        // 2. 使用 MethodSignature 获取当前被注解的 Method
        Method method = signature.getMethod();

        // 3. 从 Method 中提取 LogExecution 注解
        ApiOperationLog apiOperationLog = method.getAnnotation(ApiOperationLog.class);

        // 4. 从 LogExecution 注解中获取 description 属性
        return apiOperationLog.description();
    }

    /**
     * 入参数组转 JSON 字符串
     *
     * @param args 方法入参
     * @return 逗号分隔的 JSON 字符串
     */
    private String safeArgsToJson(Object[] args) {
        return Arrays.stream(args).map(this::safeToJson).collect(Collectors.joining(", "));
    }

    /**
     * 安全地将对象转为 JSON 字符串
     * <p>
     * 日志是旁路能力，任何序列化异常都绝不能影响业务调用，所以这里有两层保护：
     * <ol>
     *   <li>先把对象里类型为 {@link MultipartFile} 的字段脱敏成「文件名 + 大小」。
     *       MultipartFile 不能直接交给 Jackson：它的 getResource() 返回
     *       MultipartFileResource，序列化时会调用 getFile() 并抛出
     *       FileNotFoundException("cannot be resolved to absolute file path")。
     *       这一步发生在 joinPoint.proceed() 之前，一旦抛出，Controller 方法根本不会被执行
     *       ——表现为「文件分片上传接口 100% 失败」。</li>
     *   <li>序列化整体兜底，失败则降级为 toString()。</li>
     * </ol>
     * 注意：脱敏只覆盖对象自身的字段，够用即可，不递归处理嵌套结构（当前入参没有这种形态）。
     *
     * @param obj 待序列化对象
     * @return JSON 字符串，序列化失败时返回其 toString()
     */
    private String safeToJson(Object obj) {
        if (Objects.isNull(obj)) {
            return "null";
        }
        try {
            return JsonUtil.toJsonString(maskMultipartFile(obj));
        } catch (Exception ex) {
            log.warn("## 入参序列化为 JSON 失败，降级为 toString: {}", obj.getClass().getSimpleName(), ex);
            return String.valueOf(obj);
        }
    }

    /**
     * 将对象中类型为 MultipartFile 的字段替换为「文件名 + 大小」的描述文本，
     * 其余字段保持原值。对象不含文件字段时原样返回。
     *
     * @param obj 原始对象
     * @return 脱敏后的 Map，或原对象
     * @throws IllegalAccessException 反射读取字段失败
     */
    private Object maskMultipartFile(Object obj) throws IllegalAccessException {
        Field[] fields = obj.getClass().getDeclaredFields();

        // 先判断有没有文件字段，没有就直接返回原对象，避免所有入参都被转成 Map 影响 JSON 结构
        boolean hasMultipartFile = Arrays.stream(fields)
                .anyMatch(field -> MultipartFile.class.isAssignableFrom(field.getType()));
        if (!hasMultipartFile) {
            return obj;
        }

        Map<String, Object> masked = new LinkedHashMap<>();
        for (Field field : fields) {
            // 跳过静态字段（如编译器生成的合成字段）
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            field.setAccessible(true);
            Object value = field.get(obj);

            if (value instanceof MultipartFile file) {
                // 只保留文件名与大小，避免把文件内容或无用对象写进日志
                masked.put(field.getName(), String.format("MultipartFile(name=%s, size=%dB)",
                        file.getOriginalFilename(), file.getSize()));
            } else {
                masked.put(field.getName(), value);
            }
        }
        return masked;
    }

}
