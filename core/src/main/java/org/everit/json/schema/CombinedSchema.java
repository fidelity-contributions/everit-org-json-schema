package org.everit.json.schema;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Validator for {@code allOf}, {@code oneOf}, {@code anyOf} schemas.
 */
public class CombinedSchema extends Schema {

    /**
     * Builder class for {@link CombinedSchema}.
     */
    public static class Builder extends Schema.Builder<CombinedSchema> {

        private ValidationCriterion criterion;

        private Collection<Schema> subschemas = new ArrayList<>();

        private boolean synthetic;

        @Override
        public CombinedSchema build() {
            return new CombinedSchema(this);
        }

        public Builder criterion(ValidationCriterion criterion) {
            this.criterion = criterion;
            return this;
        }

        public Builder subschema(Schema subschema) {
            this.subschemas.add(subschema);
            return this;
        }

        public Builder subschemas(Collection<Schema> subschemas) {
            this.subschemas = subschemas;
            return this;
        }

        public Builder isSynthetic(boolean synthetic) {
            this.synthetic = synthetic;
            return this;
        }
    }

    /**
     * Validation criterion.
     */
    @FunctionalInterface
    public interface ValidationCriterion {

        /**
         * Throws a {@link ValidationException} if the implemented criterion is not fulfilled by the
         * {@code subschemaCount} and the {@code matchingSubschemaCount}.
         *
         * @param subschemaCount
         *         the total number of checked subschemas
         * @param matchingSubschemaCount
         *         the number of subschemas which successfully validated the subject (did not throw
         *         {@link ValidationException})
         */
        void validate(int subschemaCount, int matchingSubschemaCount);

    }

    /**
     * Validation criterion for {@code allOf} schemas.
     */
    public static final ValidationCriterion ALL_CRITERION = new ValidationCriterion() {

        @Override
        public void validate(int subschemaCount, int matchingCount) {
            if (matchingCount < subschemaCount) {
                throw new ValidationException(null,
                        format("only %d subschema matches out of %d", matchingCount, subschemaCount),
                        "allOf"
                );
            }
        }

        @Override
        public String toString() {
            return "allOf";
        }

    };

    /**
     * Validation criterion for {@code anyOf} schemas.
     */
    public static final ValidationCriterion ANY_CRITERION = new ValidationCriterion() {

        @Override
        public void validate(int subschemaCount, int matchingCount) {
            if (matchingCount == 0) {
                throw new ValidationException(null, format(
                        "no subschema matched out of the total %d subschemas",
                        subschemaCount), "anyOf");
            }
        }

        @Override
        public String toString() {
            return "anyOf";
        }
    };

    /**
     * Validation criterion for {@code oneOf} schemas.
     */
    public static final ValidationCriterion ONE_CRITERION =
            new ValidationCriterion() {

                @Override
                public void validate(int subschemaCount, int matchingCount) {
                    if (matchingCount != 1) {
                        throw new ValidationException(null, format("%d subschemas matched instead of one",
                                matchingCount), "oneOf");
                    }
                }

                @Override public String toString() {
                    return "oneOf";
                }
            };

    public static Builder allOf(Collection<Schema> schemas) {
        return builder(schemas).criterion(ALL_CRITERION);
    }

    public static Builder anyOf(Collection<Schema> schemas) {
        return builder(schemas).criterion(ANY_CRITERION);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static Builder builder(Collection<Schema> subschemas) {
        return new Builder().subschemas(subschemas);
    }

    public static Builder oneOf(Collection<Schema> schemas) {
        return builder(schemas).criterion(ONE_CRITERION);
    }

    private final boolean synthetic;

    private final Collection<Schema> subschemas;

    private final Collection<Schema> sortedSubschemas;

    private final ValidationCriterion criterion;

    /**
     * Constructor.
     *
     * @param builder
     *         the builder containing the validation criterion and the subschemas to be checked
     */
    public CombinedSchema(Builder builder) {
        super(builder);
        this.synthetic = builder.synthetic;
        this.criterion = requireNonNull(builder.criterion, "criterion cannot be null");
        this.subschemas = builder.subschemas;
        this.sortedSubschemas = sortByCombinedFirst(requireNonNull(builder.subschemas, "subschemas cannot be null"));
    }

    private static int compareBySchemaType(Schema lschema, Schema rschema) {
        boolean leftSchemaIsCombined = lschema instanceof CombinedSchema;
        boolean rightIsCombined = rschema instanceof CombinedSchema;
        int defaultRetval = Integer.compare(lschema.hashCode(), rschema.hashCode());
        return leftSchemaIsCombined ?
                (rightIsCombined ? defaultRetval : -1) :
                (rightIsCombined ? 1 : defaultRetval);
    }

    // ensure subschemas of type CombinedSchema are always visited first
    private static Collection<Schema> sortByCombinedFirst(Collection<Schema> schemas) {
        return schemas.stream()
                .sorted(CombinedSchema::compareBySchemaType)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    public ValidationCriterion getCriterion() {
        return criterion;
    }

    /**
     * Returns the subschemas in the order they were added.
     * @return the subschemas in insertion order
     */
    public Collection<Schema> getSubschemas() {
        return subschemas;
    }

    /*
     * Internal method that returns the subschemas in the order they should be visited
     * by the ValidatingVisitor and the equals and hashCode methods.
     */
    Collection<Schema> subschemasWithCombinedFirst() {
        return sortedSubschemas;
    }

    public boolean hasMultipleCombinedSchemasOfSameCriterion() {
        return subschemas.stream()
            .filter(schema -> schema instanceof CombinedSchema)
            .collect(Collectors.groupingBy(schema -> ((CombinedSchema) schema).getCriterion(), Collectors.counting()))
            .values().stream()
            .anyMatch(count -> count > 1);
    }

    boolean isSynthetic() {
        return synthetic;
    }

    @Override void accept(Visitor visitor) {
        visitor.visitCombinedSchema(this);
    }

    @Override
    public boolean definesProperty(String field) {
        List<Schema> matching = new ArrayList<>();
        for (Schema subschema : subschemas) {
            if (subschema.definesProperty(field)) {
                matching.add(subschema);
            }
        }
        try {
            criterion.validate(subschemas.size(), matching.size());
        } catch (ValidationException e) {
            return false;
        }
        return true;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o instanceof CombinedSchema) {
            CombinedSchema that = (CombinedSchema) o;
            return that.canEqual(this) &&
                    Objects.equals(sortedSubschemas, that.sortedSubschemas) &&
                    Objects.equals(criterion, that.criterion) &&
                    synthetic == that.synthetic &&
                    super.equals(that);
        } else {
            return false;
        }
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), sortedSubschemas, criterion, synthetic);
    }

    @Override
    protected boolean canEqual(Object other) {
        return other instanceof CombinedSchema;
    }
}
