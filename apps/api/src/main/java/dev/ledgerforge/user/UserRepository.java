package dev.ledgerforge.user;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    @Query("""
            select u from User u
            where (:q is null or lower(u.email) like :q or lower(u.fullName) like :q)
            order by u.createdAt desc
            """)
    Page<User> search(@Param("q") String q, Pageable pageable);
}
