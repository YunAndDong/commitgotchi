package com.commitgotchi.user.domain;

import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class UserRepository {

    private final UserMapper mapper;

    public UserRepository(UserMapper mapper) {
        this.mapper = mapper;
    }

    public boolean existsByEmail(String email) {
        return mapper.existsByEmail(email);
    }

    public Optional<User> findByEmail(String email) {
        return Optional.ofNullable(mapper.findByEmail(email));
    }

    public Optional<User> findById(Long id) {
        return Optional.ofNullable(mapper.findById(id));
    }

    public Optional<User> findByIdForUpdate(Long id) {
        return Optional.ofNullable(mapper.findByIdForUpdate(id));
    }

    public List<User> findAll() {
        return mapper.findAll();
    }

    public long count() {
        return mapper.count();
    }

    public User save(User user) {
        if (user.getId() == null) {
            mapper.insert(user);
            return user;
        }
        mapper.update(user);
        return findById(user.getId()).orElse(user);
    }

    public User saveAndFlush(User user) {
        return save(user);
    }

    public int softDeleteById(long id, Instant deletedAt) {
        return mapper.softDeleteById(id, deletedAt);
    }

    public void deleteAll() {
        mapper.deleteAll();
    }
}
