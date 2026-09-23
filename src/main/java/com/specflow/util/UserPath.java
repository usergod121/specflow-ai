package com.specflow.util;

import com.specflow.exception.SpecflowException;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * 把「人打出来的路径」变成 {@link Path}。
 *
 * <p>界面上那个路径框里可以打任何东西，而 Windows 遇到 {@code " * ? |} 这些字符时，
 * {@link Path#of} 会直接抛 {@link InvalidPathException}。那属于「你打错了」，
 * 不是服务端故障——不在这里拦住，界面上收到的就是一句
 * {@code 服务端错误：java.nio.file.InvalidPathException ...}，
 * 而用户在输入框旁边看到的应该是「第几个字符不行」。
 */
public final class UserPath {

    private UserPath() {
    }

    /**
     * @param raw 用户给的路径；可以是相对路径，调用方自己决定怎么解析
     * @throws SpecflowException 根本没给路径，或者路径里有当前系统不允许的字符
     */
    public static Path parse(String raw) {
        if (raw == null) {
            throw new SpecflowException("没有给路径");
        }
        try {
            return Path.of(raw);
        } catch (InvalidPathException e) {
            int index = e.getIndex();
            throw new SpecflowException(index < 0
                    ? "这个路径用不了：" + raw
                    : "这个路径用不了：第 " + (index + 1) + " 个字符不合法");
        }
    }
}
