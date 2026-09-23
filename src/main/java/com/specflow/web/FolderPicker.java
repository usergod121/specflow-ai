package com.specflow.web;

import com.specflow.exception.SpecflowException;
import com.specflow.util.ProcessOutput;

import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 弹一个「选择文件夹」的窗口，把用户选中的目录拿回来。
 *
 * <p><b>为什么非要绕这一圈。</b>浏览器出于隐私考虑不会把系统对话框里选中的<b>绝对路径</b>
 * 交给页面：{@code <input webkitdirectory>} 和 File System Access API 都只给文件名。
 * 所以「像 IDEA 那样打开项目」不能由页面来弹——而这个服务本来就跑在用户同一台机器上，
 * 让它自己去弹就行了。这也是它能被 {@link LocalOnly} 限制在本机的原因：弹出的窗口
 * 必然出现在用户自己的桌面上。
 *
 * <p><b>三个层次，一层比一层不依赖环境</b>：
 * <ol>
 *   <li>各平台自己的系统对话框（最原生）；</li>
 *   <li>按<b>绝对路径</b>找 PowerShell，而不是把命令名丢给 PATH——PATH 不干净是常态，
 *       实测 PATH 里没有 System32 时 {@code ProcessBuilder("powershell")} 会报
 *       {@code CreateProcess error=2}，而同一个 powershell.exe 用绝对路径跑得好好的；</li>
 *   <li>系统对话框彻底弹不出来时，让 JVM 自己画一个（{@link JFileChooser}）。
 *       它不是系统对话框，但胜在不依赖任何外部程序——「打开失败」比「样式不够原生」糟得多。</li>
 * </ol>
 */
final class FolderPicker implements FolderPicking.Picker {

    /**
     * 测试用的接缝：设了这个系统属性就按它给的命令行去跑（经 shell），不弹真窗口。
     *
     * <p>真对话框没法自动点，但「跑一个命令、拿它输出的一行当结果」这层管道可以测——
     * 选中了、点了取消、命令失败，三种结果都从这一层出去。
     */
    static final String OVERRIDE = "specflow.picker";

    private static final String TITLE = "选择要打开的项目目录";

    /** 用户可能把窗口开着去干别的了，所以给得很宽；真正的上限是他自己关掉。 */
    private static final long WAIT_MINUTES = 30;

    /** 现在正开着的那个命令（用户按下 Ctrl+C 时要把它的窗口一起收掉）。 */
    private final AtomicReference<Process> running = new AtomicReference<>();

    /**
     * Windows 上的脚本。
     *
     * <p>先用一个 TopMost 的隐藏窗体当 owner：对话框是模态的、跟着 owner 走，
     * 这样它才会出现在浏览器窗口前面，而不是闷在后面等用户去找。
     */
    private static final String WINDOWS_SCRIPT = """
            Add-Type -AssemblyName System.Windows.Forms
            $owner = New-Object System.Windows.Forms.Form
            $owner.TopMost = $true
            $owner.ShowInTaskbar = $false
            $owner.WindowState = 'Minimized'
            $dlg = New-Object System.Windows.Forms.FolderBrowserDialog
            $dlg.Description = '%s'
            $dlg.ShowNewFolderButton = $true
            if ($dlg.ShowDialog($owner) -eq [System.Windows.Forms.DialogResult]::OK) {
              [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
              [Console]::Out.Write($dlg.SelectedPath)
            }
            $owner.Dispose()
            """.formatted(TITLE);

    /** macOS：用户取消时 osascript 报 -128 并以非 0 退出，那按「取消」算。 */
    private static final String MAC_SCRIPT =
            "POSIX path of (choose folder with prompt \"" + TITLE + "\")";

    /**
     * @return 用户选中的目录；他点了取消（或直接关掉窗口）返回 {@code null}
     * @throws SpecflowException 全都弹不出来，原因逐条写清楚
     */
    @Override
    public String pick() {
        String override = System.getProperty(OVERRIDE);
        if (override != null && !override.isBlank()) {
            return shellOverride(override);
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return windowsPick();
        }
        if (os.contains("mac")) {
            return pickOrCancel(List.of("osascript", "-e", MAC_SCRIPT), "osascript");
        }
        return pickOrCancel(List.of("zenity", "--file-selection", "--directory",
                "--title=" + TITLE), "zenity");
    }

    /**
     * 用户中途把服务关了：把还开着的那个窗口收掉。
     *
     * <p>不收的话，强杀/正常退出之后那个「浏览文件夹」会一直留在桌面上，
     * 而它对应的是一个已经没人管的进程。
     */
    @Override
    public void abort() {
        Process process = running.getAndSet(null);
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
        }
    }

    /**
     * Windows：优先 {@code pwsh}（.NET 6 起的 FolderBrowserDialog 才是新的那个对话框），
     * 没有就退回系统自带的 {@code powershell}（经典对话框）。都弹不出来就自己画。
     */
    private String windowsPick() {
        String encoded = Base64.getEncoder()
                .encodeToString(WINDOWS_SCRIPT.getBytes(StandardCharsets.UTF_16LE));
        List<String> failures = new ArrayList<>();
        boolean anyRan = false;
        for (String shell : shells()) {
            Result result = run(List.of(shell, "-NoProfile", "-STA", "-EncodedCommand", encoded));
            if (!result.ran()) {
                // 连进程都没起来：一个窗口都没弹过，换下一个候选是安全的
                failures.add(shell + " 起不来（" + result.launchFailure() + "）");
                continue;
            }
            anyRan = true;
            if (result.code() != 0) {
                failures.add(shell + " 退出码 " + result.code() + "：" + firstLine(result));
                break;
            }
            return blankToNull(result.out());   // 选中了，或者用户取消了
        }
        if (anyRan) {
            // 这条是真弹过窗口的（虽然它失败了）。这时候再换一个 shell、或者自己画一个，
            // 用户看到的就是「我明明点了，怎么又弹一个」——宁可把原因说清楚，让他走手动输入。
            throw new SpecflowException("弹不出选择文件夹的窗口（" + String.join("；", failures)
                    + "）。可以用「手动输入路径…」。");
        }
        return swingPick(failures);
    }

    /**
     * macOS/Linux：这两个命令取消时都是非 0 退出，所以只有「起不来」才算故障。
     */
    private String pickOrCancel(List<String> command, String name) {
        Result result = run(command);
        if (!result.ran()) {
            return swingPick(List.of(name + " 起不来（" + result.launchFailure() + "）"));
        }
        if (result.code() != 0) {
            return null;   // 用户点了取消
        }
        return blankToNull(result.out());
    }

    /**
     * 候选的 shell，按「越可能好用」排在前面。
     *
     * <p>绝对路径必须排在命令名前面：{@code pwsh} / {@code powershell} 这种写法要系统去
     * PATH 里找，而 PATH 不干净的时候（某些 IDE 拉起的进程、被精简过的启动环境）
     * 它直接 {@code CreateProcess error=2}——同一个文件用绝对路径却跑得好好的。
     * 那是一种「本该弹得出来却说弹不出来」的假故障，用户只会以为功能坏了。
     */
    static List<String> shells() {
        List<String> found = new ArrayList<>();
        Set<Path> seen = new HashSet<>();
        for (String candidate : absoluteShellPaths()) {
            // 按真实路径去重：SystemRoot 和写死的那条常常只是大小写不同，其实是同一个文件
            // （这台机器上 %SystemRoot%=C:\WINDOWS，写死的是 C:\Windows）——不去重就会试两遍，
            // 报错里同一个路径也出现两遍
            Path path = Path.of(candidate);
            if (Files.isExecutable(path) && seen.add(realPathOf(path))) {
                found.add(candidate);
            }
        }
        found.add("pwsh");
        found.add("powershell");
        return List.copyOf(found);
    }

    private static Path realPathOf(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path;
        }
    }

    private static List<String> absoluteShellPaths() {
        List<String> paths = new ArrayList<>();
        String programFiles = System.getenv("ProgramFiles");
        if (programFiles != null) {
            // PowerShell 7 的 FolderBrowserDialog 是新的那个对话框，有就用它
            paths.add(programFiles + "\\PowerShell\\7\\pwsh.exe");
        }
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot != null) {
            paths.add(systemRoot + "\\System32\\WindowsPowerShell\\v1.0\\powershell.exe");
        }
        // 环境变量本身也可能被清掉，兜一个写死的
        paths.add("C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe");
        return paths;
    }

    /**
     * 最后一道：让 JVM 自己画一个目录选择框。
     *
     * <p>它不是系统对话框，长得也不一样；但胜在<b>不依赖任何外部程序</b>——
     * 服务自己就是那个进程，只要有桌面就一定画得出来。功能上「打不开」比「不够原生」糟得多。
     *
     * @param failures 系统对话框那几条路各自失败的原因，原话带进错误信息里
     * @return 选中的目录；用户取消返回 {@code null}
     */
    static String swingPick(List<String> failures) {
        String because = String.join("；", failures);
        if (GraphicsEnvironment.isHeadless()) {
            throw new SpecflowException("弹不出选择文件夹的窗口（" + because
                    + "），这个进程也没有桌面，自己画不出来。可以用「手动输入路径…」。");
        }
        AtomicReference<String> picked = new AtomicReference<>();
        try {
            // 整个东西都必须在事件线程上建、也必须在那里配：在别的线程上 new JFileChooser()
            // 再 setCurrentDirectory，会撞上它内部的 FilePane 正在加载目录列表，
            // 抛一个 IndexOutOfBoundsException（实测在 JDK 17 上必现）。Swing 的规矩不是摆设。
            SwingUtilities.invokeAndWait(() -> {
                JFileChooser chooser = new JFileChooser();
                chooser.setDialogTitle(TITLE);
                chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                chooser.setCurrentDirectory(new File(System.getProperty("user.home", ".")));
                // 这一步会一直等用户选完
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION
                        && chooser.getSelectedFile() != null) {
                    picked.set(chooser.getSelectedFile().getAbsolutePath());
                }
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SpecflowException("等待选择文件夹时被打断了");
        } catch (InvocationTargetException | RuntimeException e) {
            // InvocationTargetException.toString() 只有类名，真正的原因在 cause 里——
            // 只报类名等于什么都没说
            Throwable cause = e instanceof InvocationTargetException && e.getCause() != null
                    ? e.getCause() : e;
            throw new SpecflowException("弹不出选择文件夹的窗口（" + because
                    + "），自己画也失败了：" + cause + "。可以用「手动输入路径…」。");
        }
        return picked.get();
    }

    /** 测试用：把给定的命令行交给 shell 跑，它输出的一行就是「用户选的路径」。 */
    private String shellOverride(String command) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Result result = windows
                ? run(List.of("cmd", "/c", command))
                : run(List.of("sh", "-c", command));
        if (!result.ran()) {
            throw new SpecflowException("跑测试用的接缝命令失败：" + result.launchFailure());
        }
        if (result.code() != 0) {
            throw new SpecflowException("跑测试用的接缝命令失败（退出码 " + result.code() + "）："
                    + firstLine(result));
        }
        return blankToNull(result.out());
    }

    private static String firstLine(Result result) {
        String detail = (result.err().isBlank() ? result.out() : result.err()).strip();
        return detail.lines().findFirst().orElse("没有更多信息");
    }

    private static String blankToNull(String text) {
        return text.isBlank() ? null : text.trim();
    }

    /**
     * 一次尝试的结果。
     *
     * @param launchFailure 连进程都没起来时的原话；起来了就是 {@code null}
     */
    private record Result(int code, String out, String err, String launchFailure) {

        boolean ran() {
            return launchFailure == null;
        }
    }

    /**
     * 跑一个命令并等它结束。
     *
     * <p>输出写进临时文件而不是管道：窗口开着的时候这个进程会一直活着，
     * 而 PowerShell 失败时往 stderr 上倒的东西可能比管道缓冲区还大——
     * 那样就会卡在 waitFor 上谁也动不了。
     */
    private Result run(List<String> command) {
        Path outFile = null;
        Path errFile = null;
        Process process;
        try {
            outFile = Files.createTempFile("specflow-picker-", ".out");
            errFile = Files.createTempFile("specflow-picker-", ".err");
            process = new ProcessBuilder(command)
                    .redirectOutput(outFile.toFile())
                    .redirectError(errFile.toFile())
                    .start();
        } catch (IOException e) {
            deleteQuietly(outFile);
            deleteQuietly(errFile);
            return new Result(-1, "", "", String.valueOf(e.getMessage()));
        }
        running.set(process);
        try {
            process.getOutputStream().close();
            // 窗口开着时这里就是在等用户挑目录，所以上限给得很宽
            if (!process.waitFor(WAIT_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new SpecflowException("选择文件夹的窗口等太久了，先关掉它再试一次");
            }
            return new Result(process.exitValue(), read(outFile), read(errFile), null);
        } catch (IOException e) {
            return new Result(-1, "", "", "读不到它的输出：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SpecflowException("等待选择文件夹时被打断了");
        } finally {
            running.compareAndSet(process, null);
            deleteQuietly(outFile);
            deleteQuietly(errFile);
        }
    }

    /**
     * 读那个临时文件。
     *
     * <p>编码识别统一放在 {@link ProcessOutput}：编译校验读 Maven 日志踩的是同一个坑
     * （中文 Windows 上 javac 的报错是 GBK），两处必须用同一套判断，否则修好一个坏另一个。
     */
    private static String read(Path file) throws IOException {
        return ProcessOutput.read(file);
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // 临时文件删不掉不值得打扰用户
        }
    }
}
