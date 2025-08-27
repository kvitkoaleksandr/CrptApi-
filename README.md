# CrptApi

**CrptApi** — класс для отправки запроса на создание документа

Цель проекта — показать реализацию HTTP-клиента, корректную обработку ошибок, простое ограничение частоты запросов.

---

## О проекте

- Один публичный класс: **`CrptApi`**
- Один публичный метод: **`createIntroduceGoods(document, signature, productGroup)`**
- Ограничение частоты: **Sliding Window** (блокирующий; настраивается в конструкторе)
- Сериализация: Jackson; `product_document` передаётся как **base64(JSON)**
- Логирование: **SLF4J** (`@Slf4j` от Lombok)
- Тесты: **JUnit 5** + встроенный **JDK HttpServer** (реальные HTTP-вызовы)

---

## Стек технологий

- Java 17+
- `java.net.http.HttpClient`
- Jackson (databind/annotations/core)
- SLF4J (+ `slf4j-simple` в runtime)
- Lombok
- JUnit 5
- Gradle (Kotlin DSL)

---

## Как это работает

1. Формируется запрос `POST /lk/documents/create?pg=<productGroup>`  
   (базовый URL по умолчанию — `https://ismp.crpt.ru/api/v3`, можно переопределить в конструкторе).
2. Заголовки: `Authorization: Bearer <token>`, `Content-Type: application/json`, `Accept: */*`.
3. Тело запроса:
    - `document_format = "MANUAL"`
    - `type = "LP_INTRODUCE_GOODS"`
    - `product_document = base64(JSON(document))`
    - `signature = <подпись>`
4. Перед отправкой запрос проходит через **rate limiter** (ожидает, если лимит исчерпан).
5. Ответ:
    - **2xx** → ожидается `{"value":"<id>"}` → возвращается `DocumentId`.
    - **401/403** → `AuthException`.
    - **4xx** → `ApiException`.
    - Другая сеть → `TransportException`.

---

## Публичный API

- **Конструкторы**
    - `CrptApi(TimeUnit unit, int requestLimit, TokenProvider tokenProvider)` — базовый URL по умолчанию.
    - `CrptApi(TimeUnit unit, int requestLimit, URI baseUri, TokenProvider tokenProvider)` — с переопределением `baseUri`.
- **Метод**
    - `createIntroduceGoods(Object document, String signature, String productGroup)` → `DocumentId`
- **TokenProvider**
    - Функциональный интерфейс `getToken()` — любая логика получения/обновления токена.
- Объект `document` может быть любым POJO/`Map`. Внутренние `Document`/`Product` — **опциональные** модели для удобства.

---

## Обработка ошибок

- `IllegalArgumentException` — некорректные аргументы (null/blank).
- `AuthException` — 401/403.
- `ApiException` — прочие 4xx.
- `TransportException` — 5xx/сетевые ошибки/некорректный 2xx без `id`.
- `RateLimitInterruptedException` — поток прервали во время ожидания лимитера.

Если в теле есть `error_message`, сообщение берётся из него; иначе — `"HTTP <код> body: <обрезанное тело>"`.

---

## Ограничение частоты

- **Sliding Window**: хранит таймстемпы вызовов и не допускает более `N` запросов за окно `TimeUnit.toNanos(1)`.
- Блокировка потока через `wait/notifyAll`.
- Простое и предсказуемое решение; легко заменить на другую стратегию при необходимости.

---

## Тестирование

`CrptApiTest` поднимает локальный **JDK HttpServer** и проверяет end-to-end:

- успешный 2xx с корректным `value`;
- 401/403 → `AuthException`;
- любые 4xx → `ApiException` (в т.ч. без тела → `"HTTP 400..."`);
- 5xx → `TransportException`;
- 2xx, но пустой `value` → `TransportException`;
- валидацию входа (`null document`, пустые `signature`/`productGroup`, пустой токен);
- I/O-ошибку (подключение к недоступному порту);
- работу лимитера (при лимите `1 req/sec` второй вызов ждёт ~1 сек.).

---

## Сборка и запуск тестов

```bash
./gradlew clean test