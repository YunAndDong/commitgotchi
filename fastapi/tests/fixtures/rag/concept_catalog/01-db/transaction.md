# Database

Intro text explains why database consistency matters for interview answers.

## Transaction

Transaction management keeps multiple SQL writes atomic, consistent, isolated, and durable.
It also explains rollback, commit, isolation level, lock behavior, and index side effects.

### Isolation Level

Isolation level controls dirty read, non-repeatable read, and phantom read behavior.
Serializable isolation is safer but may reduce concurrency in relational database systems.

```markdown
# This is not a real heading
The fenced block should not create a headingPath entry.
```

### Index

Database index structures such as B-Tree reduce lookup cost but add write overhead.
Good answers compare selectivity, cardinality, transaction workload, and query plan behavior.
