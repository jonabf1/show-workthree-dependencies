import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

public class FindJavaDeps {
    
    private static final String SRC_ROOT = "src/main/java";
    private static final Pattern IMPORT_PATTERN = Pattern.compile("^\\s*import\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern PACKAGE_PATTERN = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    
    // Detecta implementações
    private static final Pattern IMPLEMENTS_PATTERN = Pattern.compile("\\bimplements\\s+([\\w,\\s<>]+)");
    private static final Pattern EXTENDS_PATTERN = Pattern.compile("\\bextends\\s+([\\w<>]+)");
    
    // Detecta injeção de dependência
    private static final Pattern AUTOWIRED_FIELD_PATTERN = Pattern.compile("@Autowired\\s+(?:private\\s+)?([\\w<>]+)\\s+\\w+");
    private static final Pattern FINAL_FIELD_PATTERN = Pattern.compile("private\\s+final\\s+([\\w<>]+)\\s+\\w+");
    private static final Pattern CONSTRUCTOR_PARAM_PATTERN = Pattern.compile("public\\s+\\w+\\s*\\(([^)]+)\\)");
    
    // Detecta anotações Lombok
    private static final Pattern LOMBOK_REQUIRED_ARGS = Pattern.compile("@RequiredArgsConstructor");
    private static final Pattern LOMBOK_ALL_ARGS = Pattern.compile("@AllArgsConstructor");
    
    private static final boolean DEBUG = true;
    
    private final String basePackage;
    private final String srcRoot;
    private final Set<String> allDependencies = new LinkedHashSet<>();
    private final Set<String> processed = new HashSet<>();
    private final int maxDepth;
    
    private Map<String, String> classNameToPath = new HashMap<>();
    
    public FindJavaDeps(String basePackage, String srcRoot, int maxDepth) {
        this.basePackage = basePackage;
        this.srcRoot = srcRoot;
        this.maxDepth = maxDepth;
        buildClassIndex();
    }
    
    private void buildClassIndex() {
        if (DEBUG) {
            System.out.println("📚 Construindo índice de classes do projeto...");
        }
        
        try {
            Files.walk(Path.of(srcRoot))
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".java"))
                .forEach(path -> {
                    String className = path.getFileName().toString().replace(".java", "");
                    classNameToPath.put(className, path.toString().replace("\\", "/"));
                });
            
            if (DEBUG) {
                System.out.println("✅ Índice construído: " + classNameToPath.size() + " classes encontradas");
                System.out.println();
            }
        } catch (IOException e) {
            System.err.println("⚠️  Erro ao construir índice: " + e.getMessage());
        }
    }
    
    public Set<String> findAllDependencies(String filePath) {
        Set<String> dependencies = new HashSet<>();
        
        try {
            String content = Files.readString(Path.of(filePath));
            
            if (DEBUG) {
                System.out.println("  🔍 Analisando: " + filePath);
            }
            
            // 1. Imports
            Set<String> fromImports = findDependenciesFromImports(content);
            dependencies.addAll(fromImports);
            
            // 2. Implements
            Set<String> fromImplements = findDependenciesFromImplements(content);
            dependencies.addAll(fromImplements);
            
            // 3. Extends
            Set<String> fromExtends = findDependenciesFromExtends(content);
            dependencies.addAll(fromExtends);
            
            // 4. Injeção de Dependência (@Autowired)
            Set<String> fromAutowired = findDependenciesFromAutowired(content);
            dependencies.addAll(fromAutowired);
            
            // 5. Campos final (Lombok @RequiredArgsConstructor)
            Set<String> fromFinalFields = findDependenciesFromFinalFields(content);
            dependencies.addAll(fromFinalFields);
            
            // 6. Parâmetros de construtor
            Set<String> fromConstructor = findDependenciesFromConstructor(content);
            dependencies.addAll(fromConstructor);
            
            if (DEBUG) {
                System.out.println("  📊 Total de dependências: " + dependencies.size());
                System.out.println();
            }
            
        } catch (IOException e) {
            System.err.println("⚠️  Erro ao processar " + filePath + ": " + e.getMessage());
        }
        
        return dependencies;
    }
    
    private Set<String> findDependenciesFromImports(String content) {
        Set<String> dependencies = new HashSet<>();
        Matcher matcher = IMPORT_PATTERN.matcher(content);
        
        while (matcher.find()) {
            String importPath = matcher.group(1);
            
            if (importPath.startsWith(basePackage)) {
                String fileCandidate = convertPackageToPath(importPath);
                
                if (Files.exists(Path.of(fileCandidate))) {
                    dependencies.add(fileCandidate);
                    if (DEBUG) {
                        System.out.println("    📦 Import: " + importPath + " ✅");
                    }
                }
            }
        }
        
        return dependencies;
    }
    
    private Set<String> findDependenciesFromImplements(String content) {
        Set<String> dependencies = new HashSet<>();
        Matcher matcher = IMPLEMENTS_PATTERN.matcher(content);
        
        while (matcher.find()) {
            String implementsList = matcher.group(1);
            String[] interfaces = implementsList.split(",");
            
            for (String interfaceName : interfaces) {
                interfaceName = interfaceName.trim().replaceAll("<.*>", "").trim();
                
                if (DEBUG) {
                    System.out.println("    🔗 Implements: " + interfaceName);
                }
                
                String filePath = findFileByClassName(interfaceName);
                if (filePath != null) {
                    dependencies.add(filePath);
                    if (DEBUG) {
                        System.out.println("      ✅ Encontrado: " + filePath);
                    }
                }
            }
        }
        
        return dependencies;
    }
    
    private Set<String> findDependenciesFromExtends(String content) {
        Set<String> dependencies = new HashSet<>();
        Matcher matcher = EXTENDS_PATTERN.matcher(content);
        
        while (matcher.find()) {
            String className = matcher.group(1).replaceAll("<.*>", "").trim();
            
            if (DEBUG) {
                System.out.println("    ⬆️  Extends: " + className);
            }
            
            String filePath = findFileByClassName(className);
            if (filePath != null) {
                dependencies.add(filePath);
                if (DEBUG) {
                    System.out.println("      ✅ Encontrado: " + filePath);
                }
            }
        }
        
        return dependencies;
    }
    
    /**
     * Detecta campos com @Autowired
     * Ex: @Autowired private IUserService userService;
     */
    private Set<String> findDependenciesFromAutowired(String content) {
        Set<String> dependencies = new HashSet<>();
        Matcher matcher = AUTOWIRED_FIELD_PATTERN.matcher(content);
        
        while (matcher.find()) {
            String className = matcher.group(1).replaceAll("<.*>", "").trim();
            
            if (DEBUG) {
                System.out.println("    💉 @Autowired: " + className);
            }
            
            String filePath = findFileByClassName(className);
            if (filePath != null) {
                dependencies.add(filePath);
                if (DEBUG) {
                    System.out.println("      ✅ Encontrado: " + filePath);
                }
            }
        }
        
        return dependencies;
    }
    
    /**
     * Detecta campos "private final" (usado com @RequiredArgsConstructor do Lombok)
     * Ex: private final IUserService userService;
     */
    private Set<String> findDependenciesFromFinalFields(String content) {
        Set<String> dependencies = new HashSet<>();
        
        // Verifica se tem anotação Lombok
        boolean hasLombokConstructor = LOMBOK_REQUIRED_ARGS.matcher(content).find() 
                                    || LOMBOK_ALL_ARGS.matcher(content).find();
        
        if (!hasLombokConstructor && DEBUG) {
            System.out.println("    ℹ️  Sem @RequiredArgsConstructor/@AllArgsConstructor, pulando campos final");
            return dependencies;
        }
        
        Matcher matcher = FINAL_FIELD_PATTERN.matcher(content);
        
        while (matcher.find()) {
            String className = matcher.group(1).replaceAll("<.*>", "").trim();
            
            if (DEBUG) {
                System.out.println("    🏗️  Campo final (Lombok): " + className);
            }
            
            String filePath = findFileByClassName(className);
            if (filePath != null) {
                dependencies.add(filePath);
                if (DEBUG) {
                    System.out.println("      ✅ Encontrado: " + filePath);
                }
            }
        }
        
        return dependencies;
    }
    
    /**
     * Detecta parâmetros de construtor
     * Ex: public UserService(IUserRepository repo, IEmailService email) {...}
     */
    private Set<String> findDependenciesFromConstructor(String content) {
        Set<String> dependencies = new HashSet<>();
        Matcher matcher = CONSTRUCTOR_PARAM_PATTERN.matcher(content);
        
        while (matcher.find()) {
            String params = matcher.group(1);
            
            // Parse parâmetros: "IUserRepository repo, IEmailService email"
            String[] paramList = params.split(",");
            
            for (String param : paramList) {
                param = param.trim();
                
                // Extrai tipo: "IUserRepository repo" -> "IUserRepository"
                String[] parts = param.split("\\s+");
                if (parts.length >= 2) {
                    String className = parts[0].replaceAll("<.*>", "").trim();
                    
                    // Ignora tipos primitivos e básicos
                    if (isPrimitiveOrBasic(className)) {
                        continue;
                    }
                    
                    if (DEBUG) {
                        System.out.println("    🔧 Parâmetro construtor: " + className);
                    }
                    
                    String filePath = findFileByClassName(className);
                    if (filePath != null) {
                        dependencies.add(filePath);
                        if (DEBUG) {
                            System.out.println("      ✅ Encontrado: " + filePath);
                        }
                    }
                }
            }
        }
        
        return dependencies;
    }
    
    private boolean isPrimitiveOrBasic(String className) {
        Set<String> basics = Set.of("int", "long", "double", "float", "boolean", "char", 
                                     "String", "Integer", "Long", "Double", "Float", 
                                     "Boolean", "Character", "Object");
        return basics.contains(className);
    }
    
    private String findFileByClassName(String className) {
        return classNameToPath.get(className);
    }
    
    private String convertPackageToPath(String packageName) {
        return srcRoot + "/" + packageName.replace('.', '/') + ".java";
    }
    
    public Set<String> findAllDependenciesRecursive(String filePath) {
        Queue<String> toProcess = new LinkedList<>();
        toProcess.add(filePath);
        int depth = 0;
        
        while (!toProcess.isEmpty() && depth < maxDepth) {
            int batchSize = toProcess.size();
            
            if (DEBUG) {
                System.out.println("🔄 Profundidade " + depth + " - Processando " + batchSize + " arquivo(s)");
            }
            
            for (int i = 0; i < batchSize; i++) {
                String currentFile = toProcess.poll();
                
                if (processed.contains(currentFile)) {
                    continue;
                }
                
                processed.add(currentFile);
                allDependencies.add(currentFile);
                
                Set<String> deps = findAllDependencies(currentFile);
                
                for (String dep : deps) {
                    if (!processed.contains(dep)) {
                        toProcess.add(dep);
                    }
                }
            }
            
            depth++;
        }
        
        if (depth >= maxDepth) {
            System.err.println("⚠️  Atingiu profundidade máxima (" + maxDepth + ")");
        }
        
        return allDependencies;
    }
    
    public static String detectBasePackage(String filePath) {
        try {
            String content = Files.readString(Path.of(filePath));
            Matcher matcher = PACKAGE_PATTERN.matcher(content);
            
            if (matcher.find()) {
                String packageName = matcher.group(1);
                String[] parts = packageName.split("\\.");
                
                if (parts.length >= 3) {
                    return parts[0] + "." + parts[1] + "." + parts[2];
                } else if (parts.length >= 2) {
                    return parts[0] + "." + parts[1];
                }
                return packageName;
            }
        } catch (IOException e) {
            System.err.println("Erro ao detectar pacote: " + e.getMessage());
        }
        
        return null;
    }
    
    public static Set<String> getModifiedFiles() {
        Set<String> modified = new HashSet<>();
        
        try {
            Process process = Runtime.getRuntime().exec("git diff --name-only");
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.endsWith(".java")) {
                    modified.add(line);
                }
            }
            
            process.waitFor();
        } catch (IOException | InterruptedException e) {
            System.err.println("⚠️  Erro ao buscar arquivos modificados: " + e.getMessage());
        }
        
        return modified;
    }
    
    public static void main(String[] args) {
        String filePath = "C:\\Users\\usuario\\projeto\\src\\main\\java\\br\\com\\empresa\\projeto\\controller\\UserController.java";
        String basePackage = "br.com.empresa.projeto";
        String srcRoot = "src/main/java";
        int maxDepth = 150;
        
        filePath = filePath.replace("\\", "/");
        
        if (!Files.exists(Path.of(filePath))) {
            System.err.println("❌ Arquivo não encontrado: " + filePath);
            System.exit(1);
        }
        
        if (basePackage == null) {
            basePackage = detectBasePackage(filePath);
            if (basePackage == null) {
                System.err.println("❌ Não foi possível detectar o pacote base.");
                System.exit(1);
            }
        }
        
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║  🔍 ANÁLISE COMPLETA DE DEPENDÊNCIAS (IMPORTS + DI + LOMBOK) ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println("📄 Arquivo: " + filePath);
        System.out.println("📦 Pacote base: " + basePackage);
        System.out.println("📁 Raiz: " + srcRoot);
        System.out.println();
        
        FindJavaDeps finder = new FindJavaDeps(basePackage, srcRoot, maxDepth);
        Set<String> dependencies = finder.findAllDependenciesRecursive(filePath);
        
        System.out.println();
        System.out.println("════════════════════════════════════════════════════════════");
        System.out.println("✅ RESULTADO: " + dependencies.size() + " dependências");
        System.out.println("════════════════════════════════════════════════════════════");
        System.out.println();
        
        dependencies.stream().sorted().forEach(System.out::println);
        
        System.out.println("\n💡 Para adicionar ao Git:");
        System.out.println("git add " + String.join(" ", dependencies));
        
        Set<String> modified = getModifiedFiles();
        Set<String> modifiedDeps = dependencies.stream()
            .filter(modified::contains)
            .collect(Collectors.toSet());
        
        if (!modifiedDeps.isEmpty()) {
            System.out.println("\n📝 Apenas modificadas:");
            System.out.println("git add " + String.join(" ", modifiedDeps));
        }
    }
}
