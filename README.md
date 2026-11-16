# Digital Wallet — Desafio PicPay Backend

API REST de transferências entre usuários de uma carteira digital, desenvolvida como solução do
[desafio técnico de backend do PicPay](https://github.com/PicPay/picpay-desafio-backend).

O foco do projeto é a **consistência da transferência**: validações de negócio, consulta a um autorizador
externo, movimentação de saldo atômica com bloqueio pessimista e notificação do recebedor somente após o
commit.

## Sumário

- [Escopo](#escopo)
- [Tecnologias](#tecnologias)
- [Arquitetura](#arquitetura)
- [Pré-requisitos](#pré-requisitos)
- [Configuração](#configuração)
- [Executando localmente](#executando-localmente)
- [Preparando dados para testar a API](#preparando-dados-para-testar-a-api)
- [API — `POST /transfer`](#api--post-transfer)
- [Tratamento de erros](#tratamento-de-erros)
- [Integrações externas](#integrações-externas)
- [Raciocínio de arquitetura e fluxo da transferência](#raciocínio-de-arquitetura-e-fluxo-da-transferência)
- [Testes](#testes)
- [Limitações e possíveis evoluções](#limitações-e-possíveis-evoluções)

## Escopo

Implementado:

- Modelo de usuários comuns (`COMMON`) e lojistas (`MERCHANT`), com documento (CPF/CNPJ) e e-mail únicos.
- Uma carteira por usuário, com saldo nunca negativo.
- `POST /transfer`:
  - lojistas não enviam dinheiro, apenas recebem;
  - o pagador precisa ter saldo suficiente;
  - a transferência é autorizada por um serviço externo antes de ser efetivada;
  - débito, crédito e registro da transferência ocorrem em uma única transação;
  - recusas de negócio também são registradas, com o motivo.
- Notificação do recebedor (serviço externo) após transferências concluídas.

Fora do escopo: cadastro de usuários via API, autenticação, consulta de saldo/extrato e frontend
(detalhes em [Limitações](#limitações-e-possíveis-evoluções)).

## Tecnologias

| Tecnologia | Versão | Uso |
|---|---|---|
| Java | 25 | Linguagem (`java.version` no `pom.xml`) |
| Spring Boot | 4.1.2 | Web MVC, Data JPA, Validation |
| PostgreSQL | 17 (imagem `postgres:17-alpine`) | Banco de dados |
| Flyway | gerenciado pelo Spring Boot | Migrações de schema |
| Spring Security Crypto | gerenciado pelo Spring Boot | Hash BCrypt de senhas (somente a biblioteca de criptografia) |
| Maven Wrapper | Maven 3.10.0 | Build |
| JUnit 5, Mockito, AssertJ, MockMvc | gerenciados pelo Spring Boot | Testes |
| Testcontainers | gerenciado pelo Spring Boot | PostgreSQL real nos testes de integração |

Não há Lombok nem dependências de resiliência (retry/circuit breaker).

## Arquitetura

Organização em camadas, com portas na camada de aplicação e adaptadores na infraestrutura:

```
com.picpay.digitalwallet
├── domain                      Entidades JPA, regras invariantes e repositórios
│   ├── user                    User, UserType, PasswordHasher, UserRepository
│   ├── wallet                  Wallet (credit/debit), WalletService, WalletRepository (lock pessimista)
│   └── transfer                Transfer (registro imutável), TransferStatus, TransferRejectionReason
├── application                 Casos de uso e portas
│   ├── user                    UserService (cadastro de usuário + carteira; sem endpoint exposto)
│   └── transfer                TransferService (orquestração), TransactionalTransferExecutor (transação),
│                               portas TransferAuthorizer, TransferExecutor e TransferNotifier
└── infrastructure              Adaptadores
    ├── web                     TransferController, DTOs e ApiExceptionHandler (ProblemDetail)
    ├── authorizer              HttpTransferAuthorizer + configuração do RestClient
    └── notifier                HttpTransferNotifier + configuração do RestClient
```

Fluxo de uma transferência:

```
TransferController
  └─ TransferService (não transacional)
       1. valida entrada (valor positivo, até 2 casas; pagador ≠ recebedor)
       2. carrega usuários e carteiras
       3. lojista? → registra REJECTED (MERCHANT_CANNOT_TRANSFER)
       4. pré-checagem de saldo sem lock → registra REJECTED (INSUFFICIENT_BALANCE)
       5. consulta o autorizador externo (sem transação aberta)
            DENIED            → registra REJECTED (NOT_AUTHORIZED)
            TECHNICAL_FAILURE → 503, nada é registrado
            AUTHORIZED        → TransactionalTransferExecutor (transação única)
                                  trava carteiras (ordem crescente de ID), revalida saldo,
                                  debita, credita e registra COMPLETED — ou REJECTED se o saldo mudou
       6. se COMPLETED (já confirmada no banco) → notifica o recebedor (melhor esforço)
```

## Pré-requisitos

- **JDK 25**
- **Docker** com Docker Compose — para o PostgreSQL local e para os testes de integração (Testcontainers)
- Maven **não** é necessário: o projeto usa o Maven Wrapper (`mvnw` / `mvnw.cmd`)

## Configuração

A configuração da aplicação está em `src/main/resources/application.properties` e a do banco local em
`docker-compose.yml`. **Nenhuma senha fica versionada**: ambos leem a senha da mesma variável,
`DB_PASSWORD`.

| Variável | Padrão | Usada por | Descrição |
|---|---|---|---|
| `DB_PASSWORD` | **nenhum (obrigatória)** | Compose e aplicação | Senha do PostgreSQL |
| `DB_URL` | `jdbc:postgresql://localhost:5434/digital_wallet` | Aplicação | URL JDBC do PostgreSQL |
| `DB_USERNAME` | `wallet` | Aplicação | Usuário do banco (o Compose cria sempre o usuário `wallet`) |
| `AUTHORIZER_URL` | `https://util.devi.tools/api/v2/authorize` | Aplicação | Autorizador externo (mock do desafio) |
| `NOTIFIER_URL` | `https://util.devi.tools/api/v1/notify` | Aplicação | Serviço de notificação (mock do desafio) |

O arquivo [`.env.example`](.env.example) é um modelo com valores fictícios. Copie-o para `.env` (ignorado
pelo Git) e escolha uma senha local:

```bash
cp .env.example .env               # Linux/macOS
```

```powershell
Copy-Item .env.example .env        # Windows (PowerShell)
```

> **Quem lê o `.env`?** Apenas o **Docker Compose**, que o carrega automaticamente do diretório do projeto.
> A aplicação Spring Boot executada pelo Maven **não** lê o `.env`: as variáveis precisam ser definidas
> no terminal antes do `spring-boot:run` (ver [Executar a aplicação](#2-executar-a-aplicação)) ou na
> configuração de execução da IDE (Run Configuration → Environment variables).

Outras propriedades relevantes (sem variável de ambiente associada):

| Propriedade | Valor |
|---|---|
| `server.port` | `8080` |
| `app.authorizer.connect-timeout` / `app.authorizer.read-timeout` | `2s` / `3s` |
| `app.notifier.connect-timeout` / `app.notifier.read-timeout` | `2s` / `3s` |
| `spring.jpa.open-in-view` | `false` |
| `spring.jpa.hibernate.ddl-auto` | `none` (o schema é criado exclusivamente pelo Flyway) |

## Executando localmente

### 1. Subir o PostgreSQL

Com o `.env` criado (ver [Configuração](#configuração)):

```bash
docker compose up -d
```

Sobe o PostgreSQL 17 (container `picpay-postgres`) na porta **5434** do host, com o banco `digital_wallet`,
o usuário `wallet` e volume persistente `postgres_data`. O compose define um healthcheck com `pg_isready`.
Se `DB_PASSWORD` não estiver definida, o Compose interrompe a execução com a mensagem
`Defina DB_PASSWORD (veja .env.example)`.

> O PostgreSQL só aplica a senha na **primeira** inicialização do volume. Se o volume `postgres_data` já
> existir com outra senha, alterar `DB_PASSWORD` não muda a senha do banco: recrie o volume com
> `docker compose down -v` (apaga os dados locais) e suba novamente.

### 2. Executar a aplicação

Defina `DB_PASSWORD` **no mesmo terminal**, com o mesmo valor usado no `.env`, e execute pelo Maven
Wrapper.

Linux/macOS (bash/zsh):

```bash
export DB_PASSWORD='mesma-senha-do-.env'
./mvnw spring-boot:run
```

Ou, para exportar todas as variáveis do `.env` na sessão atual:

```bash
set -a; . ./.env; set +a
./mvnw spring-boot:run
```

Windows (PowerShell):

```powershell
$env:DB_PASSWORD = "mesma-senha-do-.env"
.\mvnw.cmd spring-boot:run
```

As variáveis opcionais (`DB_URL`, `DB_USERNAME`, `AUTHORIZER_URL`, `NOTIFIER_URL`) são definidas da mesma
forma.

`DB_PASSWORD` é obrigatória para executar a aplicação. Defina essa variável no ambiente do processo ou na
configuração de execução da IDE.

Na inicialização, o Flyway aplica as migrações de `src/main/resources/db/migration`
(`V1__create_users`, `V2__create_wallets`, `V3__create_transfers`). A API fica disponível em
`http://localhost:8080`.

### 3. Encerrar

```bash
docker compose down        # mantém os dados (volume postgres_data)
docker compose down -v     # remove também o volume
```

## Preparando dados para testar a API

O projeto **não expõe endpoint de cadastro** e as migrações **não inserem dados**. O cadastro existe apenas
internamente (`UserService.register`, coberto por testes), pois ficou fora do escopo desta entrega.
Para testar o `POST /transfer` localmente é preciso criar usuários e carteiras manualmente.

Opção para desenvolvimento local — inserção direta via `psql` no container, **depois** que a aplicação
tiver iniciado ao menos uma vez (para que o Flyway crie as tabelas):

```bash
docker compose exec postgres psql -U wallet -d digital_wallet
```

Sobre o `password_hash`: a coluna é obrigatória no schema, mas **não há login** e o fluxo de transferência
nunca lê a senha. O valor `dev-placeholder` é apenas um marcador para satisfazer a restrição `NOT NULL`:
**não é uma senha nem um hash BCrypt válido**, e nenhum usuário criado assim consegue se autenticar.
Nomes, documentos e e-mails (domínio reservado `example.com`) também são fictícios.

```sql
-- DADOS FICTÍCIOS, SOMENTE PARA DESENVOLVIMENTO LOCAL.
-- 'dev-placeholder' NÃO é senha nem hash válido: apenas preenche a coluna obrigatória password_hash.
INSERT INTO users (full_name, document, email, password_hash, user_type) VALUES
  ('Usuario Comum Exemplo', '00000000001',    'comum@example.com',   'dev-placeholder', 'COMMON'),
  ('Lojista Exemplo',       '00000000000100', 'lojista@example.com', 'dev-placeholder', 'MERCHANT');

INSERT INTO wallets (user_id, balance)
SELECT id, CASE user_type WHEN 'COMMON' THEN 500.00 ELSE 0.00 END
FROM users
WHERE email IN ('comum@example.com', 'lojista@example.com');

-- IDs de USUÁRIO a usar como payer/payee no POST /transfer
SELECT u.id AS user_id, u.user_type, w.id AS wallet_id, w.balance
FROM users u JOIN wallets w ON w.user_id = u.id;
```

Regras do schema respeitadas pelo exemplo: `document` com até 14 dígitos e único; e-mail único
(sem diferenciar maiúsculas); `user_type` em `COMMON`/`MERCHANT`; uma carteira por usuário; saldo `>= 0`.

> Uma alternativa mais robusta (ainda não implementada) seria um script de seed separado, ativado apenas
> por um profile de desenvolvimento e fora de `db/migration`, para nunca ser aplicado em produção.

## API — `POST /transfer`

`payer` e `payee` são **IDs de usuário** (não de carteira). `value` deve ser positivo, com até 2 casas
decimais.

### Requisição

```bash
curl -i -X POST http://localhost:8080/transfer \
  -H "Content-Type: application/json" \
  -d '{"value": 100.0, "payer": 4, "payee": 15}'
```

### Respostas

| Status | Quando | Corpo |
|---|---|---|
| `201 Created` | Transferência concluída | `TransferResponse` com `status: COMPLETED` |
| `422 Unprocessable Content` | Recusa de negócio registrada | `TransferResponse` com `status: REJECTED` e `rejectionReason` |
| `400 Bad Request` | JSON malformado, campos inválidos, pagador igual ao recebedor | ProblemDetail |
| `404 Not Found` | Usuário ou carteira inexistente | ProblemDetail |
| `415 Unsupported Media Type` | `Content-Type` diferente de `application/json` | ProblemDetail |
| `503 Service Unavailable` | Autorizador indisponível ou resposta ininterpretável (nada é registrado) | ProblemDetail |
| `500 Internal Server Error` | Erro inesperado (sem detalhes internos na resposta) | ProblemDetail |

**201 — concluída**

```json
{
  "id": 99,
  "value": 100.00,
  "payer": 4,
  "payee": 15,
  "status": "COMPLETED",
  "rejectionReason": null,
  "createdAt": "2026-11-13T12:00:00Z"
}
```

**422 — recusada**

```json
{
  "id": 100,
  "value": 100.00,
  "payer": 4,
  "payee": 15,
  "status": "REJECTED",
  "rejectionReason": "INSUFFICIENT_BALANCE",
  "createdAt": "2026-11-13T12:00:00Z"
}
```

Motivos de recusa (`rejectionReason`):

| Motivo | Situação |
|---|---|
| `MERCHANT_CANNOT_TRANSFER` | O pagador é lojista |
| `INSUFFICIENT_BALANCE` | Saldo insuficiente (na pré-checagem ou na revalidação com lock) |
| `NOT_AUTHORIZED` | O autorizador externo negou |

## Tratamento de erros

Os erros seguem o formato **ProblemDetail** ([RFC 9457](https://www.rfc-editor.org/rfc/rfc9457),
`application/problem+json`), centralizado em `ApiExceptionHandler`:

- **Validação de campos (400)** — `detail` fixo e a lista `errors` com `field` e `message`, sem ecoar o
  valor rejeitado:

  ```json
  {
    "title": "Bad Request",
    "status": 400,
    "detail": "One or more fields are invalid.",
    "errors": [ { "field": "value", "message": "must be greater than 0" } ]
  }
  ```

- **Entrada inválida de negócio (400)** — por exemplo `"detail": "payer and payee must be different users"`.
- **Não encontrado (404)** — `"detail": "User not found: 99"` ou `"Wallet not found for user: 99"`.
- **Autorizador indisponível (503)** — `"detail": "The transfer authorizer is unavailable. Try again later."`.
- **Erro inesperado (500)** — `"detail": "An unexpected error occurred."`. Stack trace, SQL e mensagens
  internas ficam apenas no log do servidor. `IllegalArgumentException` é tratada como erro interno
  (invariante violada), nunca como entrada do cliente.

Erros do próprio Spring MVC (JSON malformado, método ou mídia não suportados) são tratados pela
superclasse `ResponseEntityExceptionHandler`, também como ProblemDetail.

## Integrações externas

### Autorizador (`GET`)

`HttpTransferAuthorizer` consulta o mock do desafio **antes** da transação, sem enviar dados da
transferência (o mock os ignora). Interpretação estrita da resposta:

| Resposta | Resultado |
|---|---|
| `200` com `data.authorization = true` | Autorizada |
| `200` ou `403` com `data.authorization = false` | Negada → `REJECTED / NOT_AUTHORIZED` (422) |
| Qualquer outro caso: outro status, `403` com `true`, timeout, erro de conexão, corpo inválido, campo ausente ou não booleano, redirecionamento | Falha técnica → `503`, nada é registrado |

Redirecionamentos não são seguidos. Timeouts: conexão 2s, leitura 3s.

### Notificação do recebedor (`POST`)

`HttpTransferNotifier` envia `POST` para o mock do desafio **sem corpo**: o enunciado documenta apenas a
URL e o verbo, e nenhum dado pessoal é enviado.

- Disparada **somente** quando o executor retorna uma transferência `COMPLETED` — ou seja, depois do
  commit. Nenhuma recusa é notificada, inclusive a recusa por saldo na revalidação com lock.
- Qualquer `2xx` é sucesso. Outros status, timeout ou erro de conexão geram um log `WARN` (apenas o ID da
  transferência e o status ou tipo da falha) e **nunca** propagam exceção.
- O `TransferService` ainda protege a chamada com `try/catch`: uma falha do notificador não pode
  transformar uma transferência confirmada em erro HTTP — o que levaria o cliente a repeti-la.
- Síncrona e de melhor esforço: sem fila, sem retry; timeouts de conexão 2s e leitura 3s.

## Raciocínio de arquitetura e fluxo da transferência

Esta seção apresenta, em ordem, o problema que cada decisão procura resolver. As escolhas reduzem riscos
conhecidos de concorrência e de integração, mas **não são uma garantia absoluta** contra todas as falhas
possíveis de um sistema distribuído. As lacunas conhecidas estão em
[Limitações e possíveis evoluções](#limitações-e-possíveis-evoluções).

### 1. O problema: consistência financeira

Uma transferência altera dois saldos. O pior resultado possível é um estado "pela metade": dinheiro que
sai do pagador e não chega ao recebedor, um saldo que fica negativo ou duas transferências simultâneas
que gastam o mesmo saldo. A pergunta que orientou o desenho foi: **em que momento, e sob qual proteção,
os saldos podem ser alterados?**

### 2. Separação em domain, application e infrastructure

- **domain** guarda as regras que não podem ser quebradas, independentemente de quem chama: uma `Wallet`
  não aceita débito maior que o saldo, valores precisam ser positivos e ter no máximo 2 casas, e uma
  `Transfer` é um registro imutável. O banco repete as regras mais críticas com `CHECK` e `UNIQUE`.
- **application** orquestra o caso de uso (`TransferService`) e define **portas**, interfaces como
  `TransferAuthorizer`, `TransferExecutor` e `TransferNotifier`, sem conhecer suas implementações.
- **infrastructure** contém os detalhes técnicos: o controller HTTP, o tratamento de erros e os clientes
  HTTP dos serviços externos.

Essa separação permite testar a regra de negócio com dublês (mocks) das portas, sem rede e sem banco, e
trocar um adaptador sem alterar o caso de uso.

### 3. Validar usuários e carteiras antes de qualquer custo

O `TransferService` começa pelo que é barato e não depende de ninguém: o valor é positivo e tem até 2
casas? Pagador e recebedor são diferentes? Em seguida carrega os usuários e as carteiras; se algum não
existir, a resposta é `404`. Depois aplica as regras de negócio: lojista não envia dinheiro e o pagador
precisa ter saldo. Essas recusas são **registradas** como `REJECTED`, com o motivo, para histórico.

A checagem de saldo nesse ponto é feita **sem lock** e serve apenas para não consultar o autorizador à
toa. Ela não é a decisão final, porque o saldo pode mudar logo em seguida (ver item 8).

### 4. Consultar o autorizador antes da transação

O desafio exige uma autorização externa antes de concluir a transferência. A consulta acontece **antes**
de qualquer movimentação, e a interpretação é conservadora: só uma confirmação explícita autoriza; uma
negativa explícita vira `REJECTED / NOT_AUTHORIZED`; qualquer resposta que não possa ser interpretada com
segurança (timeout, erro, corpo inválido) é tratada como falha técnica, com `503` e nada registrado,
porque nenhuma decisão foi obtida.

### 5. Por que não manter uma transação aberta durante a chamada HTTP

Uma chamada de rede pode levar segundos. Se acontecesse dentro de uma transação que já travou as
carteiras, uma conexão do pool e os locks dessas carteiras ficariam presos durante toda a espera, e outras
transferências envolvendo as mesmas carteiras ficariam bloqueadas. Por isso o `TransferService` é
**intencionalmente não transacional**: a chamada ao autorizador ocorre sem transação e sem locks, e a
transação só começa depois da autorização, durando o mínimo necessário. Cada leitura e cada registro de
recusa feitos pelo serviço usam a transação curta do próprio repositório.

### 6. Débito e crédito na mesma transação

Com a autorização obtida, o `TransactionalTransferExecutor` (`@Transactional`) faz tudo de uma vez:
trava, revalida, debita, credita e grava a transferência `COMPLETED`. Ou tudo é confirmado no commit, ou
qualquer falha desfaz tudo; não existe o estado "debitou, mas não creditou". O `saveAndFlush` faz os erros
de banco (constraints, por exemplo) aparecerem ainda dentro da transação, e não apenas no commit.

### 7. Bloqueio pessimista e ordem crescente dos IDs

Duas transferências simultâneas do mesmo pagador poderiam ler o mesmo saldo e ambas serem concluídas. Para
evitar isso, cada carteira é lida com `SELECT ... FOR UPDATE` (`PESSIMISTIC_WRITE` em
`WalletRepository.findByIdForUpdate`): quem chega depois espera o commit de quem chegou antes. O método
exige uma transação ativa (`Propagation.MANDATORY`), para que o lock não seja obtido e liberado
imediatamente por engano.

Travar duas carteiras cria outro risco: **deadlock**. Se A→B trava A e depois B, enquanto B→A trava B e
depois A, cada transação pode ficar esperando a outra. A solução adotada é travar sempre em **ordem
crescente de ID**, independentemente de quem paga e quem recebe: as duas transferências disputam primeiro
a mesma carteira, e uma simplesmente espera a outra terminar.

### 8. Revalidar o saldo dentro da transação

Entre a pré-checagem (item 3) e o lock, outra transferência pode ter consumido o saldo. Por isso o
executor **verifica o saldo novamente, já com as carteiras travadas**. Se não houver mais saldo, registra
`REJECTED / INSUFFICIENT_BALANCE` sem mover dinheiro, mesmo que o autorizador tenha aprovado.

### 9. Notificar somente após o commit

O recebedor só deve ser avisado de algo que realmente aconteceu. Como o `TransferService` não é
transacional, quando `transferExecutor.execute(...)` retorna, a transação do executor **já foi
confirmada**. É nesse ponto, e apenas quando o status retornado é `COMPLETED`, que a notificação é
disparada. Recusas, inclusive a recusa por saldo na revalidação, nunca são notificadas.

### 10. Por que a falha da notificação não desfaz a transferência

Quando a notificação é enviada, o dinheiro já mudou de carteira e o commit já aconteceu: não há transação a
desfazer. Transformar uma falha do serviço de notificação em erro seria pior, pois o cliente receberia um
erro para uma transferência que **foi** concluída e poderia repeti-la, gerando uma duplicata. Por isso o
notificador tem contrato de **melhor esforço** (registra a falha em log, sem dados pessoais, e nunca lança
exceção) e o `TransferService` ainda protege a chamada com `try/catch`. O custo dessa escolha é que uma
notificação que falha é perdida (ver limitações).

### Outras decisões de apoio

- **Valores monetários** em `BigDecimal` com escala 2 e `RoundingMode.UNNECESSARY`: nenhum arredondamento
  silencioso.
- **Sem lazy loading fora da sessão**: `open-in-view=false`; a resposta usa os IDs de usuário da requisição
  em vez de navegar pelos relacionamentos lazy da entidade.
- **Restrições no banco** como segunda linha de defesa: saldo não negativo, valor positivo, carteiras
  distintas, status válido e unicidade de documento, e-mail (sem diferenciar maiúsculas) e carteira por
  usuário.

## Testes

```bash
./mvnw test          # Windows: .\mvnw.cmd test
```

Executa **todos** os testes. Não há separação por profile: os testes de integração fazem parte da mesma
execução e **exigem Docker em execução**, pois sobem um PostgreSQL 17 descartável via Testcontainers
(`TestcontainersConfiguration`). Nenhum teste chama a internet: os clientes HTTP são testados com
`MockRestServiceServer` ou com um servidor HTTP local em `127.0.0.1`.

| Tipo | Exemplos | Docker |
|---|---|---|
| Unitários | `TransferServiceTest`, `TransactionalTransferExecutorTest`, `WalletTest`, `TransferTest`, `UserServiceTest`, `ApiExceptionHandlerTest` | Não |
| Web (MockMvc) | `TransferControllerTest` | Não |
| Adaptadores HTTP | `HttpTransferAuthorizerTest`, `AuthorizerClientConfigurationTest`, `HttpTransferNotifierTest`, `NotifierClientConfigurationTest` | Não |
| Integração (PostgreSQL real) | `TransactionalTransferExecutorIntegrationTest`, `WalletRepositoryLockTest`, `TransferPersistenceTest`, `UserServiceIntegrationTest`, `DigitalWalletApplicationTests` | Sim |

Os testes **não** precisam de `DB_PASSWORD` nem do `docker compose`: os testes de integração recebem a
conexão do container do Testcontainers (`@ServiceConnection`).

Para executar apenas classes específicas (por exemplo, sem Docker):

```bash
./mvnw test -Dtest='TransferServiceTest,TransferControllerTest,HttpTransferNotifierTest'
```

```powershell
.\mvnw.cmd test "-Dtest=TransferServiceTest,TransferControllerTest,HttpTransferNotifierTest"
```

## Limitações e possíveis evoluções

| Limitação | Impacto | Possível evolução |
|---|---|---|
| **Sem idempotência** | Se o cliente repetir a requisição (por exemplo, após um timeout do lado dele), uma nova transferência é criada. | Aceitar uma chave de idempotência (header `Idempotency-Key`) persistida com restrição de unicidade, devolvendo o resultado original nas repetições. |
| **Sem cadastro exposto por API** | Usuários e carteiras precisam ser criados manualmente para testes (ver [Preparando dados](#preparando-dados-para-testar-a-api)). O `UserService.register` existe e é testado, mas não tem endpoint. | Expor um endpoint de cadastro, ou criar um seed ativado apenas por profile de desenvolvimento, fora de `db/migration`. |
| **Notificação síncrona, sem retry e sem garantia de entrega** | Uma notificação que falha é perdida, e a chamada síncrona pode somar alguns segundos de latência à resposta quando o serviço externo está lento (timeouts de 2s para conexão e 3s para leitura). | **Outbox Pattern**: gravar um evento "transferência concluída" na **mesma transação** do débito e do crédito e entregá-lo depois, por um processo separado com retry. O evento sobrevive a quedas da aplicação e a entrega sai do caminho da requisição. |
| **Sem autenticação/autorização** | Qualquer chamador pode informar qualquer `payer`. | Autenticar o chamador e derivar o pagador da identidade autenticada. |
| **Serviços externos são mocks públicos instáveis** | O autorizador pode falhar e gerar `503`; o payload do notificador não é documentado pelo desafio. | Ajustar os adaptadores quando houver um contrato real. |
| **Sem consulta de saldo/extrato, estorno ou cancelamento** | Fora do escopo do desafio. | Os índices `(carteira, created_at)` da tabela `transfers` já atendem a uma futura consulta de extrato. |
| `spring.jpa.show-sql=true` | Útil em desenvolvimento, ruidoso em produção. | Desabilitar por profile. |
