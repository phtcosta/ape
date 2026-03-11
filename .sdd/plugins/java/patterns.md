# Padrões Java

Padrões arquiteturais e de design comuns em projetos Java.

## Padrões Arquiteturais

### Arquitetura em Camadas (Layered)

```
┌─────────────────────────────────────┐
│          Presentation Layer         │  Controllers, Views
├─────────────────────────────────────┤
│           Business Layer            │  Services, Use Cases
├─────────────────────────────────────┤
│         Persistence Layer           │  Repositories, DAOs
├─────────────────────────────────────┤
│            Data Layer               │  Entities, Database
└─────────────────────────────────────┘
```

**Indicadores:**
- Pacotes: `controller`, `service`, `repository`, `model`
- Fluxo unidirecional de dependências (cima → baixo)
- Services injetam Repositories, não vice-versa

### Hexagonal (Ports & Adapters)

```
┌─────────────────────────────────────────────────────────┐
│                      Adapters                           │
│  ┌─────────────┐                      ┌─────────────┐  │
│  │   REST API  │                      │   Database  │  │
│  │  (Inbound)  │                      │  (Outbound) │  │
│  └──────┬──────┘                      └──────┬──────┘  │
│         │           ┌─────────┐              │         │
│         └──────────►│  Core   │◄─────────────┘         │
│                     │ Domain  │                        │
│                     └─────────┘                        │
└─────────────────────────────────────────────────────────┘
```

**Indicadores:**
- Pacotes: `adapter`, `port`, `domain`, `application`
- Interfaces de Port no domínio, implementações em adapters
- Domínio sem dependências externas

### Clean Architecture

**Indicadores:**
- Pacotes: `entity`, `usecase`, `interface`, `framework`
- Use cases orquestram entidades
- Inversão de dependência nas bordas

## Padrões de Design

### Repository Pattern

**Detecção:**
```java
// Anotação
@Repository
public class UserRepository { }

// Interface genérica
public interface JpaRepository<T, ID> { }

// Naming convention
*Repository, *Dao
```

**Características:**
- Abstrai acesso a dados
- Métodos CRUD padrão
- Query methods por convenção

### Service Layer

**Detecção:**
```java
@Service
public class UserService { }

@Transactional
public class OrderService { }
```

**Características:**
- Orquestra lógica de negócio
- Gerencia transações
- Coordena múltiplos repositories

### DTO Pattern

**Detecção:**
```java
// Naming
*DTO, *Dto, *Request, *Response, *VO

// Records (Java 14+)
public record UserDTO(String name, String email) { }

// Lombok
@Data
public class UserDto { }
```

**Características:**
- Transferência entre camadas
- Sem lógica de negócio
- Imutáveis preferencialmente

### Factory Pattern

**Detecção:**
```java
public class UserFactory {
    public User create() { }
}

// Método estático
public static User of(String name) { }
```

### Builder Pattern

**Detecção:**
```java
// Lombok
@Builder
public class User { }

// Manual
User.builder().name("x").build()
```

### Singleton Pattern

**Detecção:**
```java
// Spring (default scope)
@Component
@Service
@Repository

// Enum singleton
public enum DatabaseConnection { INSTANCE }
```

## Padrões Spring

### Dependency Injection

**Por construtor (recomendado):**
```java
@Service
public class UserService {
    private final UserRepository repository;

    public UserService(UserRepository repository) {
        this.repository = repository;
    }
}
```

**Por campo (legacy):**
```java
@Autowired
private UserRepository repository;
```

### Controller Pattern

**REST Controller:**
```java
@RestController
@RequestMapping("/api/users")
public class UserController {

    @GetMapping("/{id}")
    public ResponseEntity<User> getById(@PathVariable Long id) { }

    @PostMapping
    public ResponseEntity<User> create(@RequestBody @Valid UserDTO dto) { }
}
```

### Exception Handling

**Controller Advice:**
```java
@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Error> handleNotFound(NotFoundException e) { }
}
```

### Validation

**Bean Validation:**
```java
public class UserDTO {
    @NotNull
    @Size(min = 2, max = 50)
    private String name;

    @Email
    private String email;
}
```

## Padrões de Persistência

### JPA/Hibernate

**Entity:**
```java
@Entity
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL)
    private List<Order> orders;
}
```

### Spring Data

**Repository:**
```java
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    List<User> findByNameContaining(String name);

    @Query("SELECT u FROM User u WHERE u.active = true")
    List<User> findAllActive();
}
```

## Anti-Patterns a Documentar

| Anti-Pattern | Descrição | Indicador |
|--------------|-----------|-----------|
| God Class | Classe faz demais | > 500 linhas, muitas responsabilidades |
| Anemic Domain | Entidades sem lógica | Apenas getters/setters |
| Circular Dependency | A depende de B, B de A | Import mútuo |
| Service Locator | Busca serviços em runtime | `ApplicationContext.getBean()` |
| Field Injection | DI por campo | `@Autowired` em campo |

## Métricas de Qualidade

| Métrica | Bom | Atenção | Ruim |
|---------|-----|---------|------|
| Linhas por classe | < 200 | 200-500 | > 500 |
| Métodos por classe | < 10 | 10-20 | > 20 |
| Parâmetros por método | < 4 | 4-6 | > 6 |
| Profundidade de herança | < 3 | 3-5 | > 5 |
| Coupling | Baixo | Médio | Alto |
