# Wanaku Persistence Architecture

This document describes the Wanaku persistence layer architecture, designed as a reference for implementing similar persistence systems.

## Overview

The persistence layer follows a layered architecture:

```text
┌─────────────────────────────────────┐
│         Service Layer               │
├─────────────────────────────────────┤
│       Repository Interfaces         │  ← API module
├─────────────────────────────────────┤
│    Abstract Implementations         │  ← Infinispan module
├─────────────────────────────────────┤
│     Concrete Repositories           │
├─────────────────────────────────────┤
│    Infinispan Embedded Cache        │
├─────────────────────────────────────┤
│    SoftIndexFileStore (Disk)        │
└─────────────────────────────────────┘
```

**Key characteristics:**

- Repository pattern with generics
- Infinispan embedded cache with file-based persistence
- Protocol Buffers (proto3) for serialization
- CDI for dependency injection
- Thread-safe operations with ReentrantLock and conditional cache operations
- No transactions: each write changes one entry. Operations that change more than one entry (for example, a catalog deploy) are serialized with a lock in one process, but are not atomic across a crash

## Entity Hierarchy

### Base Interface: WanakuEntity

All persisted entities implement `WanakuEntity<K>`:

```java
public interface WanakuEntity<K> {
    K getId();
    void setId(K id);
}
```

### Labels Support: LabelsAwareEntity

Entities requiring metadata labels extend `LabelsAwareEntity<T>`:

```java
public abstract class LabelsAwareEntity<T> implements WanakuEntity<T> {
    private Map<String, String> labels;

    public Map<String, String> getLabels();
    public void setLabels(Map<String, String> labels);
    public void addLabel(String key, String value);
    public void addLabels(Map<String, String> labelsMap);
    public String getLabelValue(String labelKey);
    public boolean hasLabel(String labelKey);
    public boolean hasLabel(String labelKey, String labelValue);
    public boolean removeLabel(String labelKey);
}
```

### Concrete Entity Example

```java
public class DataStore extends LabelsAwareEntity<String> {
    private String id;
    private String name;
    private String data;  // Base64-encoded content

    // getters/setters
}
```

## Repository Interfaces

### Base Repository: WanakuRepository

```java
public interface WanakuRepository<A extends WanakuEntity, C> {
    A persist(A entity);
    List<A> listAll();
    boolean deleteById(C id);
    A findById(C id);
    boolean update(C id, A entity);
    boolean remove(Predicate<A> matching);
    int size();
    int removeByField(String fieldName, Object fieldValue);
    int removeByFields(Map<String, Object> fields);
    int removeAll();
    boolean exists(C key);
}
```

**Key operations:**

- `persist()` - Generates an ID if the ID is null. Creates the entity, or replaces the entity with the same ID.
- `update()` - Replaces an existing entity. Returns `false` and does not create the entity if the ID does not exist.
- `removeByField()/removeByFields()` - Bulk deletion using Ickle queries

`AbstractInfinispanRepository` also provides two change operations that take a `Consumer`:

- `update(id, consumer)` - Applies the change to an existing entity. Returns `false` if the entity does not exist.
- `upsert(id, consumer)` - Creates the entity if it does not exist, then applies the change.

### Label-Aware Repository: LabelAwareInfinispanRepository

```java
public interface LabelAwareInfinispanRepository<A extends LabelsAwareEntity<K>, K>
    extends WanakuRepository<A, K> {

    List<A> findAllFilterByLabelExpression(String labelExpression);
    int removeIf(String labelExpression) throws LabelExpressionParseException;
}
```

**Label expression examples:**

- `category=weather` - Exact match
- `category=weather & !action=forecast` - AND with negation
- `(category=weather | category=news) & environment=production` - Complex boolean

### Domain-Specific Interfaces

```java
public interface DataStoreRepository
    extends LabelAwareInfinispanRepository<DataStore, String> {
    List<DataStore> findByName(String name);
}
```

## Abstract Implementations

### AbstractInfinispanRepository

Base implementation providing thread-safe CRUD operations:

```java
public abstract class AbstractInfinispanRepository<A extends WanakuEntity<K>, K>
    implements WanakuRepository<A, K> {

    protected final EmbeddedCacheManager cacheManager;
    private final ReentrantLock lock = new ReentrantLock();

    protected abstract Class<A> entityType();
    protected abstract String entityName();
    protected abstract K newId();

    @Override
    public A persist(A entity) {
        lock.lock();
        try {
            if (entity.getId() == null) {
                entity.setId(newId());
            }
            getCache().put(entity.getId(), entity);
            return entity;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean update(K id, A entity) {
        lock.lock();
        try {
            // replace() writes only if the key exists
            return getCache().replace(id, entity) != null;
        } finally {
            lock.unlock();
        }
    }

    public int removeByField(String fieldName, Object fieldValue) {
        // Uses Ickle query: DELETE FROM EntityClass WHERE field = :value
    }

    protected Cache<K, A> getCache() {
        return cacheManager.getCache(entityName());
    }
}
```

### Record Metadata for Data Stores

`InfinispanDataStoreRepository` stores each entry as a `DataStoreRecord`. This class extends the SDK `DataStore` type with metadata that the repository manages:

| Field | Description |
|-------|-------------|
| `createdAt` | The time of the first write (UTC). |
| `updatedAt` | The time of the last write (UTC). |
| `createdBy` | The actor that created the entry. Empty, because Barn has no identity source. |
| `updatedBy` | The actor that made the last change. Empty, because Barn has no identity source. |
| `revision` | A number that starts at 1 and increments on each write. |

The repository applies these rules:

- Each write builds a new record. The repository keeps `createdAt` and `createdBy` from the previous record.
- The repository ignores metadata values that a client sends.
- Writes use the per-key atomic operations `compute`, `computeIfPresent` and `putIfAbsent`.
- Reads return detached copies. A change to a returned object does not change the stored record.
- Records written before the metadata fields existed load with `revision` 0 and empty timestamps. The next write sets `revision` to 1.

The REST API returns the metadata fields in data store responses.

### Optimistic Concurrency for Data Stores

`DataStoreRepository` provides conditional operations:

- `create(dataStore)` - Creates an entry. Throws `EntityAlreadyExistsException` if an entry has the same name or ID.
- `update(id, dataStore, expectedRevision)` - Replaces an entry if the stored revision is equal to `expectedRevision`. Returns `null` if the entry does not exist. Throws `RevisionConflictException` if the revisions are different. Throws `EntityAlreadyExistsException` if a rename uses the name of another entry.
- `deleteById(id, expectedRevision)` - Deletes an entry if the stored revision is equal to `expectedRevision`.

A `null` expected revision skips the revision check.

The repository uses these mechanisms:

- The repositories are CDI singletons. All callers share one repository instance and one lock.
- All data store writes hold the repository lock. Checks that read more than one entry, such as name uniqueness, are atomic in the JVM.
- Writes that depend on the stored value use the conditional cache operations `replace(key, old, new)`, `remove(key, old)` and `putIfAbsent`.
- Generic deletes (`deleteById`, `removeByFields`, `removeAll`, `removeIf`) also hold the lock. `removeIf` counts only the entries that it removed.

The cache mode is `LOCAL` and the file store is not shared. These guarantees apply to one Barn process. Do not start more than one process on the same store directory.

### Queries

Barn does not embed a query index (Lucene). Ickle queries scan the cache in memory.

`StoredDataStore` extends `DataStoreRecord` and adds derived properties for queries: `type` (label `wanaku.type`), `catalogName` (label `wanaku.catalog-name`) and `updatedAtMillis`. The repository stores `StoredDataStore` and returns `DataStoreRecord` copies.

The queries of `DataStoreRepository` are `findByName`, `findByType`, `findByTypeAndCatalogName` and `listPage`.
Service catalog and template lookups use `findByTypeAndCatalogName`, so Barn does not decode each ZIP package to find a catalog by name.

Label expressions (`findAllFilterByLabelExpression`, `removeIf`) still filter in memory, because labels are a map and an expression can use any key.
Use `findByType` when you need only the `wanaku.type` label.

### AbstractLabelAwareInfinispanRepository

Extends base with label filtering:

```java
public abstract class AbstractLabelAwareInfinispanRepository<A extends LabelsAwareEntity<K>, K>
    extends AbstractInfinispanRepository<A, K>
    implements LabelAwareInfinispanRepository<A, K> {

    @Override
    public List<A> findAllFilterByLabelExpression(String labelExpression) {
        Predicate<A> predicate = LabelExpressionParser.parse(labelExpression);
        return listAll().stream()
            .filter(predicate)
            .collect(Collectors.toList());
    }

    @Override
    public int removeIf(String labelExpression) {
        Predicate<A> predicate = LabelExpressionParser.parse(labelExpression);
        List<A> toRemove = listAll().stream()
            .filter(predicate)
            .collect(Collectors.toList());
        toRemove.forEach(e -> deleteById(e.getId()));
        return toRemove.size();
    }
}
```

## Concrete Repository Implementation

```java
public class InfinispanDataStoreRepository
    extends AbstractLabelAwareInfinispanRepository<DataStore, String>
    implements DataStoreRepository {

    public InfinispanDataStoreRepository(EmbeddedCacheManager cacheManager,
                                          Configuration configuration) {
        super(cacheManager, configuration);
    }

    @Override
    protected String entityName() {
        return "datastore";
    }

    @Override
    protected Class<DataStore> entityType() {
        return DataStore.class;
    }

    @Override
    protected String newId() {
        return UUID.randomUUID().toString();
    }

    @Override
    public List<DataStore> findByName(String name) {
        QueryFactory queryFactory = Search.getQueryFactory(getCache());
        Query<DataStore> query = queryFactory.create(
            "from ai.wanaku.capabilities.sdk.api.types.DataStore d where d.name = :name");
        query.setParameter("name", name);
        return query.execute().list();
    }
}
```

## Serialization Layer

### Proto Schema Definition

Create `.proto` files in `src/main/resources/proto/`:

```protobuf
// data_store.proto
syntax = "proto3";
package ai.wanaku.capabilities.sdk.api.types;

message DataStore {
  string id = 1;
  string name = 2;
  string data = 3;
  map<string, string> labels = 4;
  int64 created_at = 5;   // milliseconds since the epoch, 0 = unknown
  int64 updated_at = 6;
  string created_by = 7;
  string updated_by = 8;
  int64 revision = 9;
}
```

New fields always get new field numbers. Records written with the earlier fields stay readable.

**Field type mappings:**

- Strings → `string`
- Labels → `map<string, string>`
- Nested objects → `message`
- Lists → `repeated`

### Marshaller Implementation

Implement `MessageMarshaller<T>` for each entity:

```java
public class DataStoreMarshaller implements MessageMarshaller<DataStore> {

    @Override
    public String getTypeName() {
        return DataStore.class.getCanonicalName();
    }

    @Override
    public Class<? extends DataStore> getJavaClass() {
        return DataStore.class;
    }

    @Override
    public DataStore readFrom(ProtoStreamReader reader) throws IOException {
        DataStore dataStore = new DataStore();
        dataStore.setId(reader.readString("id"));
        dataStore.setName(reader.readString("name"));
        dataStore.setData(reader.readString("data"));
        dataStore.setLabels(reader.readMap("labels",
            new HashMap<>(), String.class, String.class));
        return dataStore;
    }

    @Override
    public void writeTo(ProtoStreamWriter writer, DataStore dataStore)
            throws IOException {
        writer.writeString("id", dataStore.getId());
        writer.writeString("name", dataStore.getName());
        writer.writeString("data", dataStore.getData());
        writer.writeMap("labels", dataStore.getLabels(),
            String.class, String.class);
    }
}
```

This example shows the basic pattern. The actual `DataStoreMarshaller` reads and writes `DataStoreRecord`, including the metadata fields, under the `DataStore` message name.

> **Note:** The caches store Java objects. Ickle queries match the class of the stored values, not the proto message name. Queries on the data store cache use `from ai.wanaku.core.services.api.DataStoreRecord`. Override `queryEntityName()` in a repository when the stored class is different from `entityType()`.

### Schema Initializer

Register schemas and marshallers:

```java
public class DataStoreSchema
    extends AbstractWanakuSerializationContextInitializer {

    @Override
    public String getProtoFileName() {
        return "data_store.proto";
    }

    @Override
    public void registerMarshallers(SerializationContext serCtx) {
        serCtx.registerMarshaller(new DataStoreMarshaller());
    }
}
```

**Base class loads proto file from classpath:**

```java
public abstract class AbstractWanakuSerializationContextInitializer
    implements SerializationContextInitializer {

    public abstract String getProtoFileName();

    public String getProtoFile() {
        return ResourceUtils.getResourceAsString(
            getClass(), "/proto/" + getProtoFileName());
    }

    public void registerSchema(SerializationContext serCtx) {
        serCtx.registerProtoFiles(
            FileDescriptorSource.fromString(getProtoFileName(), getProtoFile()));
    }

    public abstract void registerMarshallers(SerializationContext serCtx);
}
```

## CDI Configuration

### Infinispan Configuration Provider

```java
public class InfinispanConfigurationProvider {

    @ConfigProperty(name = "wanaku.persistence.infinispan.base-folder",
                   defaultValue = "${wanaku.home}/barn/")
    String baseFolder;

    @ConfigProperty(name = "wanaku.persistence.infinispan.max-entries", defaultValue = "10000")
    int maxEntries;

    @ConfigProperty(name = "wanaku.persistence.infinispan.file-store", defaultValue = "true")
    boolean fileStore;

    @Produces
    Configuration newConfiguration() {
        ConfigurationBuilder builder = new ConfigurationBuilder();
        builder.clustering()
                .cacheMode(CacheMode.LOCAL)
                .memory()
                .storage(StorageType.HEAP)
                .maxCount(maxEntries);

        if (fileStore) {
            String location = WanakuHome.expandPlaceholders(baseFolder);
            builder.persistence()
                    .passivation(false)
                    .addSoftIndexFileStore()
                    .dataLocation(location)
                    .indexLocation(location)
                    .shared(false)
                    .preload(true)
                    .purgeOnStartup(false);
        }
        return builder.build();
    }
}
```

**Configuration options:**

- `CacheMode.LOCAL` - Single-node caching. The caches are not shared between processes.
- `StorageType.HEAP` with `maxCount` - The caches keep up to `wanaku.persistence.infinispan.max-entries` entries in memory. With the file store, entries removed from memory stay on disk.
- `addSoftIndexFileStore()` - File-based persistence with the Infinispan SoftIndexFileStore.
- `passivation(false)` - Write-through: every write goes to the store.
- `preload(true)` - The store loads all entries into memory at startup.
- `purgeOnStartup(false)` - The store keeps its content at startup.
- `wanaku.persistence.infinispan.file-store=false` - Turns off disk persistence (used by the tests).
- Configurable storage location via `wanaku.persistence.infinispan.base-folder`

### Repository Producer

```java
public class InfinispanPersistenceConfiguration {

    @Inject
    EmbeddedCacheManager cacheManager;

    @Inject
    Configuration configuration;

    @Produces
    @Singleton
    DataStoreRepository dataStoreRepository() {
        return new InfinispanDataStoreRepository(cacheManager, configuration);
    }

    @Produces
    @Singleton
    ForwardReferenceRepository forwardReferenceRepository() {
        return new InfinispanForwardReferenceRepository(cacheManager, configuration);
    }

    // Additional producers for each repository type
}
```

Annotate each producer with `@Singleton`. Without a scope, CDI creates a new repository for each injection point, and each instance has its own lock.

## Usage Patterns

### Injecting Repositories

```java
@ApplicationScoped
public class DataStoreService {

    @Inject
    DataStoreRepository repository;

    public DataStore create(String name, String data) {
        DataStore store = new DataStore();
        store.setName(name);
        store.setData(Base64.getEncoder().encodeToString(data.getBytes()));
        store.addLabel("type", "config");
        return repository.persist(store);
    }

    public List<DataStore> findByLabel(String expression) {
        return repository.findAllFilterByLabelExpression(expression);
    }
}
```

### Label-Based Queries

```java
// Find all with specific label
List<ToolReference> tools = toolRepo.findAllFilterByLabelExpression("category=weather");

// Complex expression with AND/OR/NOT
List<ResourceReference> resources = resourceRepo.findAllFilterByLabelExpression(
    "(type=file | type=database) & !environment=test");

// Remove by label expression
int removed = toolRepo.removeIf("deprecated=true & !protected=true");
```

### Bulk Operations

```java
// Remove by single field
int count = repository.removeByField("namespace", "old-namespace");

// Remove by multiple fields (AND condition)
Map<String, Object> fields = Map.of(
    "namespace", "test",
    "type", "temporary"
);
int count = repository.removeByFields(fields);
```

## Directory Structure

The persistence layer is located within the backend module:

```text
apps/wanaku-barn-backend/src/main/java/ai/wanaku/backend/core/persistence/
├── api/
│   └── {Domain}Repository.java
├── infinispan/
│   ├── AbstractInfinispanRepository.java
│   ├── AbstractLabelAwareInfinispanRepository.java
│   ├── Infinispan{Domain}Repository.java
│   ├── InfinispanPersistenceConfiguration.java
│   ├── codeexecution/
│   │   └── InfinispanCodeTaskRepository.java
│   ├── providers/
│   │   └── InfinispanConfigurationProvider.java
│   └── protostream/
│       ├── marshaller/
│       │   └── {Domain}Marshaller.java
│       └── schema/
│           ├── AbstractWanakuSerializationContextInitializer.java
│           └── {Domain}Schema.java
```

## Implementation Checklist

When adding a new entity type:

1. **Entity Class**
   - [ ] Implement `WanakuEntity<K>` or extend `LabelsAwareEntity<T>`
   - [ ] Define fields with getters/setters

2. **Repository Interface**
   - [ ] Extend `WanakuRepository` or `LabelAwareInfinispanRepository`
   - [ ] Add domain-specific query methods

3. **Repository Implementation**
   - [ ] Extend appropriate abstract class
   - [ ] Implement `entityName()`, `entityType()`, `newId()`
   - [ ] Implement domain-specific query methods

4. **Proto Schema**
   - [ ] Create `.proto` file in `resources/proto/`
   - [ ] Define message with field numbers

5. **Marshaller**
   - [ ] Implement `MessageMarshaller<T>`
   - [ ] Handle all fields in `readFrom()` and `writeTo()`

6. **Schema Initializer**
   - [ ] Extend `AbstractWanakuSerializationContextInitializer`
   - [ ] Return proto filename
   - [ ] Register marshaller

7. **CDI Producer**
   - [ ] Add `@Produces` method in configuration class

## Key Design Decisions

1. **Embedded Cache**: Uses Infinispan embedded mode for simplicity and zero external dependencies. Data persists to local file system.

2. **Local Cache Mode**: Single-node operation. For distributed scenarios, change to `CacheMode.DIST_SYNC` or `REPL_SYNC`.

3. **ReentrantLock**: Each repository is a singleton with one lock. Writes and deletes hold the lock. Data store writes also use conditional cache operations and revision checks.

4. **Ickle Queries**: Infinispan's query language for bulk operations. More efficient than iterating and removing individually.

5. **Label Expressions**: In-memory filtering with parsed predicates. Lookups by name, by type and by catalog name use Ickle queries on the derived properties of `StoredDataStore` instead.

6. **Proto3 Serialization**: Efficient binary format with forward/backward compatibility. Field numbers must not change once deployed. Register each schema initializer in `META-INF/services/org.infinispan.protostream.SerializationContextInitializer`. `FileStoreRestartTest` loads the schemas only from this file and checks that the data survives a restart.

7. **Schema Version**: `SchemaMigrations` stores a schema version and runs ordered, idempotent migration steps at startup. Add a step and increment `SchemaMigrations.CURRENT_VERSION` when existing data needs a change. See [Backup, Restore and Upgrade](backup-and-upgrade.md).
