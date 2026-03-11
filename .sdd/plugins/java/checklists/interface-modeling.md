# Checklist: Modelagem de Interfaces - Java

**Linguagem**: Java
**Propósito**: Guiar análise de interfaces em componentes Java

## Tipos de Interface em Java

### Interfaces Programáticas (Module View)

| Mecanismo Java | Tipo de Interface | Exemplo |
|---------------|-------------------|---------|
| `interface` | Contrato explícito | `public interface UserService { ... }` |
| Classe pública (métodos públicos) | Contrato implícito | `public class UserServiceImpl { public User find(...) }` |
| Classe abstrata | Contrato parcial | `abstract class BaseRepository<T> { ... }` |
| `record` | Data Transfer Object | `public record UserDTO(String name, String email) {}` |
| `enum` | Conjunto de constantes | `enum OrderStatus { PENDING, COMPLETED }` |

### Interfaces de Comunicação (C&C View)

| Mecanismo Java | Tipo de Interface | Exemplo |
|---------------|-------------------|---------|
| REST Controller | Endpoint HTTP | `@GetMapping("/users/{id}")` |
| gRPC Service | Endpoint RPC | `service UserService { rpc GetUser(...) }` |
| `@JmsListener` / `@KafkaListener` | Endpoint de mensagem | `@KafkaListener(topics = "orders")` |
| Spring Events | Evento interno | `@EventListener` / `ApplicationEventPublisher` |
| WebSocket endpoint | Comunicação bidirecional | `@ServerEndpoint("/ws/chat")` |

---

## Análise por Seção do Template de Interface

### Seção 1. Identidade da Interface

**Em Java, interfaces são identificadas por:**
- Nome da `interface` Java (ex: `IUserService`, `UserRepository`)
- Nome do controller + path base (ex: `UserController` → `/api/users`)
- Nome do serviço gRPC/SOAP
- Versionamento: package (ex: `com.example.api.v2`) ou URL path (`/api/v2/users`)

**Exemplo:**
```java
// Interface explícita com identidade clara
public interface IOrderService {
    // v1.0 - Interface de gerenciamento de pedidos
}

// Endpoint REST com versionamento por URL
@RestController
@RequestMapping("/api/v2/orders")
public class OrderController { ... }
```

### Seção 2. Recursos

**Em Java, recursos são:**
- Métodos públicos de interfaces/classes
- Endpoints REST (GET/POST/PUT/DELETE)
- Operações de mensageria (handlers de filas/tópicos)

#### 2a. Sintaxe (Assinatura)

```java
// Recurso: buscar pedido por ID
Order findById(Long id);

// Recurso: REST endpoint
@GetMapping("/{id}")
ResponseEntity<OrderDTO> getOrder(@PathVariable Long id);
```

#### 2b. Semântica (Pré/Pós-condições)

```java
/**
 * Busca pedido por ID.
 *
 * @param id ID do pedido (não null, > 0)
 * @return Pedido encontrado
 * @throws OrderNotFoundException se pedido não existir
 *
 * Pré-condições: id != null && id > 0
 * Pós-condições: retorno != null || exceção lançada
 * Estado: nenhuma alteração (consulta pura)
 */
Order findById(Long id);
```

#### 2c. Tratamento de Erros

```java
// Exceções típicas em interfaces Java
public interface UserService {
    User find(Long id) throws UserNotFoundException;        // 1a: dados inválidos
    User create(UserDTO dto) throws ValidationException;    // 1a: dados incorretos
    void delete(Long id) throws AccessDeniedException;      // 1b: estado errado (permissão)
}

// Em REST: mapeamento exceção → HTTP status
@ExceptionHandler(UserNotFoundException.class)   // → 404
@ExceptionHandler(ValidationException.class)      // → 400
@ExceptionHandler(AccessDeniedException.class)    // → 403
```

### Seção 3. Tipos de Dados e Constantes

```java
// DTOs como tipos de dados da interface
public record CreateUserRequest(
    @NotBlank String name,
    @Email String email,
    @Size(min = 8) String password
) {}

public record UserResponse(
    Long id, String name, String email, LocalDateTime createdAt
) {}

// Constantes da interface
public interface CacheConfig {
    Duration DEFAULT_TTL = Duration.ofMinutes(30);
    int MAX_ENTRIES = 1000;
}

// Enums como tipos da interface
public enum OrderStatus {
    PENDING, PROCESSING, COMPLETED, CANCELLED
}
```

### Seção 4. Error Handling (Geral)

**Padrões comuns em Java:**

| Padrão | Mecanismo | Exemplo |
|--------|-----------|---------|
| Exceções checked | `throws` na assinatura | `void save() throws IOException` |
| Exceções unchecked | Runtime | `throw new IllegalArgumentException()` |
| Result/Either | Retorno encapsulado | `Result<User, Error> find(Long id)` |
| `@ControllerAdvice` | Handler global REST | `@ExceptionHandler(Exception.class)` |
| `@ResponseStatus` | HTTP status em exceção | `@ResponseStatus(HttpStatus.NOT_FOUND)` |

### Seção 5. Variabilidade

**Mecanismos de variabilidade em Java:**

```java
// Spring Profiles como variabilidade
@Profile("production")
@Service
public class ProductionEmailService implements EmailService { ... }

@Profile("dev")
@Service
public class MockEmailService implements EmailService { ... }

// @ConditionalOn* como variabilidade
@ConditionalOnProperty(name = "feature.newCheckout", havingValue = "true")
@Service
public class NewCheckoutService implements CheckoutService { ... }

// Parâmetros de configuração
@Value("${app.cache.ttl:30}")
private int cacheTtlMinutes;

@ConfigurationProperties(prefix = "app.order")
public class OrderConfig {
    private int maxRetries = 3;
    private Duration timeout = Duration.ofSeconds(30);
}
```

### Seção 6. Características de Atributos de Qualidade

```java
// Thread safety
@Service
public class CounterService {
    private final AtomicInteger counter = new AtomicInteger(0);
    // Thread-safe: múltiplos atores simultâneos
}

// Performance / SLA
@Cacheable("users")
public User findById(Long id); // Resposta < 100ms com cache

@Async
public CompletableFuture<Report> generateReport(); // Não bloqueia chamador

// Rate limiting
@RateLimited(requests = 100, period = Duration.ofMinutes(1))
public ResponseEntity<Data> getData();

// Timeout
@Transactional(timeout = 5) // 5 segundos
public void processOrder(Order order);
```

### Seção 7. Rationale e Design Issues

Registrar decisões como:
- Por que interface X expõe DTOs em vez de entidades?
- Por que REST em vez de gRPC?
- Por que retornar `Optional<T>` em vez de null?

### Seção 8. Guia de Uso

```java
// Exemplo de uso típico da interface
@Service
public class OrderProcessor {
    private final OrderService orderService;
    private final PaymentService paymentService;

    public void processOrder(Long orderId) {
        // 1. Buscar pedido (interface OrderService)
        Order order = orderService.findById(orderId);

        // 2. Processar pagamento (interface PaymentService)
        PaymentResult result = paymentService.charge(order.getTotal());

        // 3. Atualizar status
        orderService.updateStatus(orderId, OrderStatus.COMPLETED);
    }
}
```

---

## Interfaces Providas vs Requeridas em Java

### Interfaces Providas

```java
// Provida: o que este elemento expõe
@RestController
@RequestMapping("/api/users")
public class UserController {
    // Provê: GET /api/users/{id}
    // Provê: POST /api/users
    // Provê: PUT /api/users/{id}
    // Provê: DELETE /api/users/{id}
}
```

### Interfaces Requeridas

```java
// Requerida: o que este elemento precisa do ambiente
@Service
public class UserService {
    // Requer: UserRepository (interface de persistência)
    private final UserRepository userRepository;
    // Requer: PasswordEncoder (interface de segurança)
    private final PasswordEncoder passwordEncoder;
    // Requer: EventPublisher (interface de eventos)
    private final ApplicationEventPublisher eventPublisher;

    // Injeção de dependência = declaração de interfaces requeridas
    public UserService(UserRepository repo, PasswordEncoder encoder,
                       ApplicationEventPublisher events) { ... }
}
```

---

## Checklist Rápido - Java

- [ ] Verificar `interface` Java explícitas e métodos públicos de classes
- [ ] Verificar endpoints REST (`@RequestMapping`, `@GetMapping`, etc.)
- [ ] Verificar handlers de mensageria (`@KafkaListener`, `@JmsListener`, etc.)
- [ ] Verificar event listeners (`@EventListener`)
- [ ] Identificar DTOs/records usados na interface
- [ ] Mapear exceções por recurso
- [ ] Identificar `@Profile` e `@ConditionalOn*` (variabilidade)
- [ ] Verificar anotações de QA: `@Cacheable`, `@Async`, `@Transactional`
- [ ] Listar dependências injetadas no construtor (interfaces requeridas)
