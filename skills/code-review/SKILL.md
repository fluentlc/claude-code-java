---
name: code-review
description: Perform thorough code reviews with security, performance, and maintainability analysis. Use when user asks to review code, check for bugs, or audit a codebase.
tags: quality, security, review
---

# Code Review Skill

You now have expertise in conducting comprehensive code reviews. Follow this structured approach:

## Review Checklist

### 1. Security (Critical)

Check for:
- [ ] **Injection vulnerabilities**: SQL injection, command injection, XSS, LDAP injection
- [ ] **Authentication issues**: Hardcoded credentials, weak authentication mechanisms
- [ ] **Authorization flaws**: Missing access controls, IDOR, privilege escalation
- [ ] **Data exposure**: Sensitive data in logs, error messages, or stack traces
- [ ] **Cryptography**: Weak algorithms, improper key management, plaintext secrets
- [ ] **Dependencies**: Known vulnerabilities in third-party libraries

```bash
# Quick security scans for Java projects
mvn dependency-check:check          # OWASP dependency check
./gradlew dependencyCheckAnalyze    # Gradle equivalent
grep -r "password\|secret\|api_key\|apiKey" --include="*.java" --include="*.yml" --include="*.properties"
```

### 2. Correctness

Check for:
- [ ] **Logic errors**: Off-by-one, null handling, edge cases
- [ ] **Race conditions**: Concurrent access without synchronization, shared mutable state
- [ ] **Resource leaks**: Unclosed streams, connections, or resources (missing try-with-resources)
- [ ] **Error handling**: Swallowed exceptions, overly broad catch blocks, missing error paths
- [ ] **Type safety**: Unchecked casts, raw types, missing generics

### 3. Performance

Check for:
- [ ] **N+1 queries**: Database/API calls in loops (common with JPA/Hibernate)
- [ ] **Memory issues**: Large object allocations, retained references, improper caching
- [ ] **Blocking operations**: Blocking I/O in reactive/async code paths
- [ ] **Inefficient algorithms**: O(n^2) when O(n) is possible, unnecessary object creation
- [ ] **Missing caching**: Repeated expensive computations or database lookups
- [ ] **String concatenation**: Using `+` in loops instead of `StringBuilder`

### 4. Maintainability

Check for:
- [ ] **Naming**: Clear, consistent, descriptive (follows Java conventions)
- [ ] **Complexity**: Methods > 50 lines, deep nesting > 3 levels, cyclomatic complexity
- [ ] **Duplication**: Copy-pasted code blocks violating DRY
- [ ] **Dead code**: Unused imports, unreachable branches, commented-out code
- [ ] **Comments**: Outdated Javadoc, redundant comments, or missing documentation where needed
- [ ] **SOLID principles**: Single responsibility, proper abstractions, dependency injection

### 5. Testing

Check for:
- [ ] **Coverage**: Critical paths and business logic tested
- [ ] **Edge cases**: Null, empty, boundary values, error conditions
- [ ] **Mocking**: External dependencies properly isolated (Mockito, WireMock)
- [ ] **Assertions**: Meaningful, specific checks (not just `assertNotNull`)
- [ ] **Test naming**: Descriptive names following conventions (e.g., `shouldReturnEmpty_whenInputIsNull`)

## Review Output Format

```markdown
## Code Review: [file/component name]

### Summary
[1-2 sentence overview]

### Critical Issues
1. **[Issue]** (line X): [Description]
   - Impact: [What could go wrong]
   - Fix: [Suggested solution]

### Improvements
1. **[Suggestion]** (line X): [Description]

### Positive Notes
- [What was done well]

### Verdict
[ ] Ready to merge
[ ] Needs minor changes
[ ] Needs major revision
```

## Common Patterns to Flag

### Java
```java
// Bad: SQL injection
String query = "SELECT * FROM users WHERE id = " + userId;
stmt.executeQuery(query);
// Good: Use parameterized queries
PreparedStatement ps = conn.prepareStatement("SELECT * FROM users WHERE id = ?");
ps.setString(1, userId);

// Bad: Resource leak
InputStream is = new FileInputStream("file.txt");
// processing without close
// Good: try-with-resources
try (InputStream is = new FileInputStream("file.txt")) {
    // processing
}

// Bad: Swallowed exception
try { riskyOperation(); } catch (Exception e) { }
// Good: Log or rethrow
try { riskyOperation(); } catch (Exception e) {
    log.error("Operation failed", e);
    throw new ServiceException("Operation failed", e);
}

// Bad: Mutable shared state without synchronization
private List<String> cache = new ArrayList<>();
// Good: Use concurrent collections or synchronization
private List<String> cache = new CopyOnWriteArrayList<>();

// Bad: N+1 query in JPA
List<Order> orders = orderRepo.findAll();
orders.forEach(o -> o.getItems().size()); // triggers lazy load per order
// Good: Use fetch join
@Query("SELECT o FROM Order o JOIN FETCH o.items")
List<Order> findAllWithItems();
```

### Spring Boot
```java
// Bad: Hardcoded secrets
@Value("sk-abc123xyz")
private String apiKey;
// Good: Use externalized configuration
@Value("${api.key}")
private String apiKey;

// Bad: Missing validation
@PostMapping("/users")
public User create(@RequestBody User user) { ... }
// Good: Validate input
@PostMapping("/users")
public User create(@Valid @RequestBody User user) { ... }
```

## Review Commands

```bash
# Show recent changes
git diff HEAD~5 --stat
git log --oneline -10

# Find potential issues
grep -rn "TODO\|FIXME\|HACK\|XXX" --include="*.java" .
grep -rn "password\|secret\|token" --include="*.java" --include="*.properties" --include="*.yml" .

# Check for common issues
mvn spotbugs:check           # Static analysis
mvn checkstyle:check         # Code style
./gradlew spotbugsMain       # Gradle equivalent

# Run tests with coverage
mvn test jacoco:report
./gradlew test jacocoTestReport

# Check dependencies
mvn versions:display-dependency-updates
./gradlew dependencyUpdates
```

## Review Workflow

1. **Understand context**: Read PR description, linked issues, and acceptance criteria
2. **Run the code**: Build, run tests, start locally if possible (`mvn spring-boot:run`)
3. **Read top-down**: Start with controllers/entry points, follow the call chain
4. **Check tests**: Are changes tested? Do tests pass? Is coverage adequate?
5. **Security scan**: Run SpotBugs, OWASP dependency check
6. **Manual review**: Use checklist above, focus on business logic correctness
7. **Write feedback**: Be specific, suggest fixes, be kind and constructive
