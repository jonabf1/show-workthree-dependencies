import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

/**
 * Encontra recursivamente todas as dependências de uma classe Java.
 * 
 * Compilar: javac FindJavaDeps.java
 * Executar: java FindJavaDeps
 */
public class FindJavaDeps {
    
    private static final String SRC_ROOT = "src/main/java";
    private static final Pattern IMPORT_PATTERN = Pattern.compile("^\\s*import\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern PACKAGE_PATTERN = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    
    private final String basePackage;
    private final String srcRoot;
    private final Set<String> allDependencies = new LinkedHashSet<>();
    private final Set<String> processed = new HashSet<>();
    private final int maxDepth;
    
    public FindJavaDeps(String basePackage, String srcRoot, int maxDepth) {
        this.basePackage = basePackage;
        this.srcRoot = srcRoot;
        this.maxDepth = maxDepth;
    }
    
    /**
     * Extrai imports de um arquivo Java.
     */
    public Set<String> findLocalImports(String filePath) {
        Set<String> imports = new HashSet<>();
        
        try {
            String content = Files.readString(Path.of(filePath));
            Matcher matcher = IMPORT_PATTERN.matcher(content);
            
            while (matcher.find()) {
                String importPath = matcher.group(1);
                
                // Filtra apenas imports do projeto
                if (importPath.startsWith(basePackage)) {
                    String fileCandidate = convertPackageToPath(importPath);
                    
                    if (Files.exists(Path.of(fileCandidate))) {
                        imports.add(fileCandidate);
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("⚠️  Erro ao processar " + filePath + ": " + e.getMessage());
        }
        
        return imports;
    }
    
    /**
     * Converte nome de pacote para caminho de arquivo.
     * Ex: com.example.service.UserService → src/main/java/com/example/service/UserService.java
     */
    private String convertPackageToPath(String packageName) {
        return srcRoot + "/" + packageName.replace('.', '/') + ".java";
    }
    
    /**
     * Encontra todas as dependências recursivamente.
     */
    public Set<String> findAllDependenciesRecursive(String filePath) {
        Queue<String> toProcess = new LinkedList<>();
        toProcess.add(filePath);
        int depth = 0;
        
        while (!toProcess.isEmpty() && depth < maxDepth) {
            int batchSize = toProcess.size();
            
            for (int i = 0; i < batchSize; i++) {
                String currentFile = toProcess.poll();
                
                if (processed.contains(currentFile)) {
                    continue;
                }
                
                processed.add(currentFile);
                allDependencies.add(currentFile);
                
                // Encontra dependências diretas
                Set<String> deps = findLocalImports(currentFile);
                
                // Adiciona novas dependências para processar
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
    
    /**
     * Detecta automaticamente o pacote base de um arquivo.
     */
    public static String detectBasePackage(String filePath) {
        try {
            String content = Files.readString(Path.of(filePath));
            Matcher matcher = PACKAGE_PATTERN.matcher(content);
            
            if (matcher.find()) {
                String packageName = matcher.group(1);
                // Pega os 2 primeiros níveis: com.example.controller → com.example
                String[] parts = packageName.split("\\.");
                if (parts.length >= 2) {
                    return parts[0] + "." + parts[1];
                }
                return packageName;
            }
        } catch (IOException e) {
            System.err.println("Erro ao detectar pacote: " + e.getMessage());
        }
        
        return null;
    }
    
    /**
     * Filtra apenas arquivos modificados no Git.
     */
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
        // ============================================================
        // 🔥 COLE SEU PATH ABSOLUTO AQUI:
        // Aceita tanto Windows (C:\path\to\file) quanto Linux (/path/to/file)
        // ============================================================
        String filePath = "C:\\Users\\seu-usuario\\projetos\\meu-projeto\\src\\main\\java\\com\\example\\controller\\UserController.java";
        
        // ============================================================
        // 🔧 OPCIONAL: Defina o pacote base manualmente (deixe null para auto-detectar)
        // ============================================================
        String basePackage = null; // auto-detecta
        // String basePackage = "com.example"; // ou defina manualmente
        
        // ============================================================
        // 🔧 OPCIONAL: Ajuste a raiz do src se for diferente
        // ============================================================
        String srcRoot = "src/main/java";
        
        // ============================================================
        // 🔧 OPCIONAL: Ajuste profundidade máxima
        // ============================================================
        int maxDepth = 50;
        
        // ============================================================
        
        // Normaliza o path (aceita \ do Windows e / do Linux/Mac)
        filePath = filePath.replace("\\", "/");
        
        if (!Files.exists(Path.of(filePath))) {
            System.err.println("❌ Arquivo não encontrado: " + filePath);
            System.err.println("💡 Verifique se:");
            System.err.println("   - O caminho está correto");
            System.err.println("   - Você está executando na raiz do projeto");
            System.err.println("   - O arquivo realmente existe nesse local");
            System.exit(1);
        }
        
        // Detecta ou usa pacote base fornecido
        if (basePackage == null) {
            basePackage = detectBasePackage(filePath);
            if (basePackage == null) {
                System.err.println("❌ Não foi possível detectar o pacote base.");
                System.err.println("💡 Defina manualmente na variável 'basePackage' (ex: \"com.example\")");
                System.exit(1);
            }
        }
        
        System.out.println("🔍 Buscando dependências de: " + filePath);
        System.out.println("📦 Pacote base detectado: " + basePackage);
        System.out.println("📁 Raiz do código: " + srcRoot);
        System.out.println("🔢 Profundidade máxima: " + maxDepth);
        System.out.println();
        
        FindJavaDeps finder = new FindJavaDeps(basePackage, srcRoot, maxDepth);
        Set<String> dependencies = finder.findAllDependenciesRecursive(filePath);
        
        System.out.println("✅ Encontradas " + dependencies.size() + " dependências:\n");
        
        dependencies.stream()
            .sorted()
            .forEach(System.out::println);
        
        System.out.println("\n💡 Para adicionar ao Git:");
        System.out.println("git add " + String.join(" ", dependencies));
        
        System.out.println("\n📝 Dependências modificadas no Git:");
        Set<String> modified = getModifiedFiles();
        Set<String> modifiedDeps = dependencies.stream()
            .filter(modified::contains)
            .collect(Collectors.toSet());
        
        if (modifiedDeps.isEmpty()) {
            System.out.println("  (nenhuma)");
        } else {
            modifiedDeps.stream()
                .sorted()
                .forEach(dep -> System.out.println("  " + dep));
            
            System.out.println("\n💡 Para adicionar apenas modificadas:");
            System.out.println("git add " + String.join(" ", modifiedDeps));
        }
    }
}
