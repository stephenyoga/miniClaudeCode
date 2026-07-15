// ToolRegistry.java - 工具注册表
package com.claudecode.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表 —— 管理所有可被 LLM 调用的工具。
 *
 * LLM 通过 Function Calling 看到工具列表，决定调用哪个工具。
 * 工具列表会作为 tool definitions 随每条消息发给 LLM（见 Agent.getToolDefinitions）。
 *
 * 内置 6 个工具：
 * | 工具名 | 用途 |
 * |--------|------|
 * | read_file | 读取文件内容 |
 * | write_file | 写入/覆盖文件 |
 * | append_file | 追加内容到文件 |
 * | list_dir | 列出目录（支持递归）|
 * | execute_command | 执行 Shell 命令（自动适配 Windows UTF-8）|
 * | create_project | 创建项目（Java/Python/React/Vue 模板）|
 *
 * 每个工具由 Tool record 定义：名称 + 描述 + JSON Schema 参数定义 + 执行逻辑。
 */
public class ToolRegistry {

    private static final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Tool> tools = new HashMap<>();

    /**
     * 构造函数，注册所有内置工具
     */
    public ToolRegistry() {
        registerFileTools();
        registerShellTools();
        registerCodeTools();
    }

    /**
     * 注册文件操作工具
     */
    private void registerFileTools() {
        // read_file 工具
        tools.put("read_file", new Tool(
                "read_file",
                "读取文件内容，用于查看代码、配置文件等",
                createParameters(new Param("path", "string", "文件路径", true)),
                args -> {
                    String path = args.get("path");
                    try {
                        String content = Files.readString(Path.of(path));
                        return "文件内容:\n" + content;
                    } catch (Exception e) {
                        return "读取文件失败: " + e.getMessage();
                    }
                }
        ));

        // write_file 工具
        tools.put("write_file", new Tool(
                "write_file",
                "写入文件内容，覆盖原有内容",
                createParameters(
                        new Param("path", "string", "文件路径", true),
                        new Param("content", "string", "文件内容", true)
                ),
                args -> {
                    String path = args.get("path");
                    String content = args.get("content");
                    try {
                        Files.writeString(Path.of(path), content);
                        return "文件已写入: " + path;
                    } catch (Exception e) {
                        return "写入文件失败: " + e.getMessage();
                    }
                }
        ));

        // append_file 工具
        tools.put("append_file", new Tool(
                "append_file",
                "追加内容到文件末尾",
                createParameters(
                        new Param("path", "string", "文件路径", true),
                        new Param("content", "string", "要追加的内容", true)
                ),
                args -> {
                    String path = args.get("path");
                    String content = args.get("content");
                    try {
                        Files.writeString(Path.of(path), content,
                                java.nio.file.StandardOpenOption.APPEND,
                                java.nio.file.StandardOpenOption.CREATE);
                        return "内容已追加到: " + path;
                    } catch (Exception e) {
                        return "追加文件失败: " + e.getMessage();
                    }
                }
        ));

        // list_dir 工具
        tools.put("list_dir", new Tool(
                "list_dir",
                "列出目录内容",
                createParameters(
                        new Param("path", "string", "目录路径，默认当前目录", false),
                        new Param("recursive", "boolean", "是否递归列出子目录", false)
                ),
                args -> {
                    String path = args.getOrDefault("path", ".");
                    boolean recursive = Boolean.parseBoolean(args.getOrDefault("recursive", "false"));
                    try {
                        StringBuilder output = new StringBuilder();
                        if (recursive) {
                            Files.walk(Path.of(path))
                                    .forEach(p -> {
                                        int depth = (int) Path.of(path).relativize(p).getNameCount();
                                        output.append("  ".repeat(depth));
                                        output.append(p.getFileName());
                                        output.append(Files.isDirectory(p) ? "/" : "");
                                        output.append("\n");
                                    });
                        } else {
                            Files.list(Path.of(path))
                                    .forEach(p -> {
                                        output.append(Files.isDirectory(p) ? "[DIR] " : "[FILE] ");
                                        output.append(p.getFileName());
                                        output.append("\n");
                                    });
                        }
                        return output.toString();
                    } catch (Exception e) {
                        return "列出目录失败: " + e.getMessage();
                    }
                }
        ));
    }

    /**
     * 注册Shell命令工具
     */
    private void registerShellTools() {
        // execute_command 工具
        tools.put("execute_command", new Tool(
                "execute_command",
                "执行Shell命令，用于编译、运行、Git操作等",
                createParameters(
                        new Param("command", "string", "要执行的命令", true),
                        new Param("cwd", "string", "工作目录", false)
                ),
                args -> {
                    String command = args.get("command");
                    String cwd = args.get("cwd");
                    try {
                        ProcessBuilder pb = new ProcessBuilder();

                        // 根据操作系统设置命令，Windows 下自动切 UTF-8 代码页
                        if (System.getProperty("os.name").toLowerCase().contains("win")) {
                            pb.command("cmd.exe", "/c", "chcp 65001 >nul & " + command);
                        } else {
                            pb.command("bash", "-c", command);
                        }

                        if (cwd != null && !cwd.isBlank()) {
                            pb.directory(new java.io.File(cwd));
                        }

                        pb.redirectErrorStream(true);
                        Process process = pb.start();

                        String charset = "UTF-8";

                        StringBuilder output = new StringBuilder();
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(process.getInputStream(), charset))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                output.append(line).append("\n");
                            }
                        }

                        int exitCode = process.waitFor();
                        return String.format("命令执行完成 (exit code: %d)\n%s",
                                exitCode, output);
                    } catch (Exception e) {
                        return "执行命令失败: " + e.getMessage();
                    }
                }
        ));
    }

    /**
     * 注册代码工具
     */
    private void registerCodeTools() {
        // create_project 工具
        tools.put("create_project", new Tool(
                "create_project",
                "创建项目结构，支持多种模板",
                createParameters(
                        new Param("name", "string", "项目名称", true),
                        new Param("template", "string", "项目模板: java/maven, python, react, vue", false)
                ),
                args -> {
                    String name = args.get("name");
                    String template = args.getOrDefault("template", "java/maven");
                    try {
                        createProject(name, template);
                        return "项目创建成功: " + name;
                    } catch (Exception e) {
                        return "创建项目失败: " + e.getMessage();
                    }
                }
        ));
    }

    /**
     * 创建项目结构
     */
    private void createProject(String name, String template) throws Exception {
        Path projectDir = Path.of(name);

        switch (template.toLowerCase()) {
            case "java/maven", "java" -> createJavaMavenProject(projectDir);
            case "python" -> createPythonProject(projectDir);
            case "react" -> createReactProject(projectDir);
            case "vue" -> createVueProject(projectDir);
            default -> throw new IllegalArgumentException("不支持的模板类型: " + template);
        }
    }

    private void createJavaMavenProject(Path dir) throws Exception {
        Files.createDirectories(dir);

        // pom.xml
        String pomContent = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n" +
                "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
                "         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 \n" +
                "         http://maven.apache.org/xsd/maven-4.0.0.xsd\">\n" +
                "    <modelVersion>4.0.0</modelVersion>\n" +
                "    <groupId>com.example</groupId>\n" +
                "    <artifactId>" + dir.getFileName() + "</artifactId>\n" +
                "    <version>1.0-SNAPSHOT</version>\n" +
                "    <properties>\n" +
                "        <maven.compiler.source>21</maven.compiler.source>\n" +
                "        <maven.compiler.target>21</maven.compiler.target>\n" +
                "    </properties>\n" +
                "</project>";
        Files.writeString(dir.resolve("pom.xml"), pomContent);

        // Main.java
        Path srcMain = dir.resolve("src/main/java/com/example");
        Files.createDirectories(srcMain);
        String mainContent = "package com.example;\n\n" +
                "public class Main {\n" +
                "    public static void main(String[] args) {\n" +
                "        System.out.println(\"Hello, World!\");\n" +
                "    }\n" +
                "}";
        Files.writeString(srcMain.resolve("Main.java"), mainContent);
    }

    private void createPythonProject(Path dir) throws Exception {
        Files.createDirectories(dir);

        // requirements.txt
        Files.writeString(dir.resolve("requirements.txt"), "# Project dependencies\n");

        // main.py
        String mainContent = "def main():\n" +
                "    print(\"Hello, World!\")\n\n" +
                "if __name__ == \"__main__\":\n" +
                "    main()";
        Files.writeString(dir.resolve("main.py"), mainContent);

        // __init__.py
        Files.writeString(dir.resolve("__init__.py"), "");
    }

    private void createReactProject(Path dir) throws Exception {
        Files.createDirectories(dir);

        // package.json
        String pkgContent = "{\n" +
                "  \"name\": \"" + dir.getFileName() + "\",\n" +
                "  \"version\": \"1.0.0\",\n" +
                "  \"private\": true,\n" +
                "  \"dependencies\": {\n" +
                "    \"react\": \"^18.0.0\",\n" +
                "    \"react-dom\": \"^18.0.0\"\n" +
                "  },\n" +
                "  \"scripts\": {\n" +
                "    \"start\": \"react-scripts start\",\n" +
                "    \"build\": \"react-scripts build\"\n" +
                "  }\n" +
                "}";
        Files.writeString(dir.resolve("package.json"), pkgContent);

        // src/App.jsx
        Path srcDir = dir.resolve("src");
        Files.createDirectories(srcDir);
        String appContent = "function App() {\n" +
                "  return <h1>Hello, React!</h1>;\n" +
                "}\n" +
                "export default App;";
        Files.writeString(srcDir.resolve("App.jsx"), appContent);
    }

    private void createVueProject(Path dir) throws Exception {
        Files.createDirectories(dir);

        // package.json
        String pkgContent = "{\n" +
                "  \"name\": \"" + dir.getFileName() + "\",\n" +
                "  \"version\": \"1.0.0\",\n" +
                "  \"private\": true,\n" +
                "  \"dependencies\": {\n" +
                "    \"vue\": \"^3.0.0\"\n" +
                "  }\n" +
                "}";
        Files.writeString(dir.resolve("package.json"), pkgContent);

        // src/App.vue
        Path srcDir = dir.resolve("src");
        Files.createDirectories(srcDir);
        String appContent = "<template>\n" +
                "  <h1>Hello, Vue!</h1>\n" +
                "</template>";
        Files.writeString(srcDir.resolve("App.vue"), appContent);
    }

    /**
     * 创建参数定义（JSON Schema格式）
     */
    private JsonNode createParameters(Param... params) {
        ObjectNode parameters = mapper.createObjectNode();
        parameters.put("type", "object");
        ObjectNode properties = parameters.putObject("properties");
        ArrayNode required = parameters.putArray("required");

        for (Param param : params) {
            ObjectNode prop = properties.putObject(param.name());
            prop.put("type", param.type());
            prop.put("description", param.description());
            if (param.required()) {
                required.add(param.name());
            }
        }

        return parameters;
    }

    /**
     * 获取所有工具定义（用于传给LLM）
     */
    public List<Tool> getAllTools() {
        return List.copyOf(tools.values());
    }

    /**
     * 获取指定工具
     */
    public Tool getTool(String name) {
        return tools.get(name);
    }

    /**
     * 调用工具执行
     */
    public String callTool(String name, Map<String, String> args) {
        Tool tool = tools.get(name);
        if (tool == null) {
            return "Error: 工具不存在: " + name;
        }
        try {
            return tool.executor().execute(args);
        } catch (Exception e) {
            return "执行工具失败 [" + name + "]: " + e.getMessage();
        }
    }

    /**
     * 执行工具（与 callTool 同义，适配 Agent 调用）
     */
    public String executeTool(String toolName, Map<String, String> args) {
        return callTool(toolName, args);
    }

    /**
     * 获取工具数量
     */
    public int getToolCount() {
        return tools.size();
    }
}