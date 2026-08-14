/**
 * Persistence layer API for the Wanaku system.
 * <p>
 * This package defines repository interfaces for data access and persistence
 * operations within Wanaku. The repositories provide CRUD operations and
 * query capabilities for managing entities such as namespaces,
 * forward references, and data stores.
 * <p>
 * The persistence layer is designed to be implementation-agnostic, with concrete
 * implementations provided in separate modules (e.g., core-persistence-infinispan).
 * <p>
 * Key repository interfaces include:
 * <ul>
 *   <li>{@link ai.wanaku.backend.core.persistence.api.WanakuRepository} - Base repository interface</li>
 *   <li>{@link ai.wanaku.backend.core.persistence.api.ForwardReferenceRepository} - Forward reference persistence</li>
 *   <li>{@link ai.wanaku.backend.core.persistence.api.NamespaceRepository} - Namespace persistence</li>
 * </ul>
 *
 * @see ai.wanaku.capabilities.sdk.api.types
 */
package ai.wanaku.backend.core.persistence.api;
