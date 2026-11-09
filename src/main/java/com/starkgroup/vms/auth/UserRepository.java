package com.starkgroup.vms.auth;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /**
     * Locks the rows of all enabled users with this role (SELECT ... FOR UPDATE, ordered by id so concurrent callers
     * lock in the same order) for the rest of the transaction. Used before disabling or demoting an ADMIN: a second
     * transaction waits, PostgreSQL then re-checks the WHERE clause, and it sees the committed result (ADR 0024).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.role = :role and u.enabled = true order by u.id")
    List<User> findEnabledByRoleForUpdate(@Param("role") Role role);
}
