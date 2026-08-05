package name.abuchen.portfolio.rest.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.Classification;
import name.abuchen.portfolio.model.Classification.Assignment;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.InvestmentVehicle;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.Taxonomy;

public final class TaxonomiesHandler
{
    private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}"); //$NON-NLS-1$

    private static final Set<String> CATEGORY_FIELDS = Set.of("name", "parent", "color", "note", "weight", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                    "position"); //$NON-NLS-1$

    /**
     * What a write returns: the whole taxonomy, so a client can replace the tree
     * it holds rather than reconcile a fragment, plus the one-line description
     * the change log records.
     */
    public record Result(JsonObject entity, String taxonomyName, String detail)
    {
    }

    private TaxonomiesHandler()
    {
    }

    /**
     * The taxonomies the file defines, with their category trees.
     * <p>
     * This is the key to the {@code classifications} object on a holding, which is
     * keyed by taxonomy id and names its categories by path: without this list a
     * client has an id it cannot label and no way to know which categories exist but
     * hold nothing. The tree is what lets it show an empty category as empty rather
     * than not at all.
     */
    public static JsonElement list(Client client)
    {
        return EntityJson.envelope(client.getTaxonomies(), EntityJson::toJson);
    }

    public static JsonObject get(Client client, String taxonomyId)
    {
        return EntityJson.toJson(find(client, taxonomyId));
    }

    /**
     * Creates an empty taxonomy, exactly as the application's own "new taxonomy"
     * does: the root classification carries the same name, because the root
     * <em>is</em> the taxonomy - it is never reported as a category.
     */
    public static Result create(Client client, JsonObject body)
    {
        var errors = new ArrayList<ApiException.FieldError>();
        var name = requiredText(body, "name", errors); //$NON-NLS-1$

        if (!errors.isEmpty())
            throw ApiException.validation(errors);

        var taxonomy = new Taxonomy(name);
        taxonomy.setRootNode(new Classification(UUID.randomUUID().toString(), name));

        client.addTaxonomy(taxonomy);
        client.markDirty();

        return new Result(EntityJson.toJson(taxonomy), name, "created"); //$NON-NLS-1$
    }

    /**
     * Applies a JSON Merge Patch to the taxonomy itself. The root classification
     * is renamed with it: the two are one name to the user, and letting them
     * drift leaves a tree whose root disagrees with the tab above it.
     */
    public static Result patch(Client client, String taxonomyId, JsonObject body)
    {
        var taxonomy = find(client, taxonomyId);

        var errors = new ArrayList<ApiException.FieldError>();
        rejectUnknownFields(body, Set.of("name"), errors); //$NON-NLS-1$

        var name = body.has("name") ? requiredText(body, "name", errors) : null; //$NON-NLS-1$ //$NON-NLS-2$

        if (!errors.isEmpty())
            throw ApiException.validation(errors);

        if (name == null || name.equals(taxonomy.getName()))
            return new Result(EntityJson.toJson(taxonomy), taxonomy.getName(), null);

        var before = taxonomy.getName();
        taxonomy.setName(name);
        taxonomy.getRoot().setName(name);
        client.markDirty();

        return new Result(EntityJson.toJson(taxonomy), name, "renamed from '" + before + "'"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Deletes the taxonomy and every category in it. Nothing blocks it: a
     * taxonomy holds no data of its own, only a classification of instruments
     * and cash accounts that continue to exist without it - which is why the
     * application does not ask either.
     */
    public static String delete(Client client, String taxonomyId)
    {
        var taxonomy = find(client, taxonomyId);

        client.removeTaxonomy(taxonomy);
        client.markDirty();

        return taxonomy.getName();
    }

    /**
     * Adds a category, by default at the end of its parent's children and with
     * whatever target weight its siblings leave unclaimed - which is what the
     * application's own "add category" does, so a first child starts at 100% and
     * the next at nothing.
     */
    public static Result addCategory(Client client, String taxonomyId, JsonObject body)
    {
        var taxonomy = find(client, taxonomyId);

        var errors = new ArrayList<ApiException.FieldError>();
        rejectUnknownFields(body, CATEGORY_FIELDS, errors);

        var name = requiredText(body, "name", errors); //$NON-NLS-1$
        var parent = parentFor(taxonomy, null, body, errors);
        var color = color(body, errors);
        var note = optionalText(body, "note", errors); //$NON-NLS-1$
        var weight = weight(body, errors);
        var position = position(body, errors);

        if (!errors.isEmpty())
            throw ApiException.validation(errors);

        var unclaimed = Math.max(0, Classification.ONE_HUNDRED_PERCENT - parent.getChildrenWeight());

        var category = new Classification(parent, UUID.randomUUID().toString(), name, color);
        category.setNote(note);
        category.setWeight(weight != null ? weight : unclaimed);
        parent.addChild(category);
        placeAt(parent, category, position);

        client.markDirty();

        return new Result(EntityJson.toJson(taxonomy), taxonomy.getName(),
                        "added category '" + name + "'"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Applies a JSON Merge Patch to one category. {@code parent} moves it -
     * with its whole subtree - and an explicit null moves it to the top level,
     * the root being the taxonomy rather than a category. {@code position} is
     * the index among its siblings, clamped to the end of the list.
     */
    public static Result patchCategory(Client client, String taxonomyId, String categoryId, JsonObject body)
    {
        var taxonomy = find(client, taxonomyId);
        var category = findCategory(taxonomy, categoryId);

        var errors = new ArrayList<ApiException.FieldError>();
        rejectUnknownFields(body, CATEGORY_FIELDS, errors);

        var name = body.has("name") ? requiredText(body, "name", errors) : null; //$NON-NLS-1$ //$NON-NLS-2$
        var parent = body.has("parent") ? parentFor(taxonomy, category, body, errors) : null; //$NON-NLS-1$
        var color = color(body, errors);
        var note = optionalText(body, "note", errors); //$NON-NLS-1$
        var weight = weight(body, errors);
        var position = position(body, errors);

        if (!errors.isEmpty())
            throw ApiException.validation(errors);

        var changes = new ArrayList<String>();

        if (name != null && !name.equals(category.getName()))
        {
            changes.add("category '" + category.getName() + "' renamed to '" + name + "'"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            category.setName(name);
        }
        if (body.has("color") && !Objects.equals(color, category.getColor())) //$NON-NLS-1$
        {
            changes.add("category '" + category.getName() + "' recolored"); //$NON-NLS-1$ //$NON-NLS-2$
            category.setColor(color);
        }
        if (body.has("note") && !Objects.equals(note, category.getNote())) //$NON-NLS-1$
        {
            changes.add("note of category '" + category.getName() + "' changed"); //$NON-NLS-1$ //$NON-NLS-2$
            category.setNote(note);
        }
        if (weight != null && weight.intValue() != category.getWeight())
        {
            changes.add("target weight of category '" + category.getName() + "' changed"); //$NON-NLS-1$ //$NON-NLS-2$
            category.setWeight(weight);
        }

        if (parent != null && parent != category.getParent())
        {
            changes.add("category '" + category.getName() + "' moved to '" + parent.getName() + "'"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            category.getParent().getChildren().remove(category);
            category.setParent(parent);
            parent.addChild(category);
            placeAt(parent, category, position);
        }
        else if (position != null)
        {
            changes.add("category '" + category.getName() + "' reordered"); //$NON-NLS-1$ //$NON-NLS-2$
            placeAt(category.getParent(), category, position);
        }

        if (changes.isEmpty())
            return new Result(EntityJson.toJson(taxonomy), taxonomy.getName(), null);

        client.markDirty();

        return new Result(EntityJson.toJson(taxonomy), taxonomy.getName(), String.join("; ", changes)); //$NON-NLS-1$
    }

    /**
     * Deletes the category and everything below it. The instruments and cash
     * accounts it classified are not touched - they simply stop being
     * classified in this taxonomy, which is what the application does too.
     */
    public static Result deleteCategory(Client client, String taxonomyId, String categoryId)
    {
        var taxonomy = find(client, taxonomyId);
        var category = findCategory(taxonomy, categoryId);

        category.getParent().getChildren().remove(category);
        client.markDirty();

        return new Result(EntityJson.toJson(taxonomy), taxonomy.getName(),
                        "deleted category '" + category.getName() + "'"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Assigns an instrument or a cash account to a category, or changes the share
     * already assigned there - the same call either way, so a client can retry
     * one after a 423 without first asking which it is.
     * <p>
     * A vehicle can be split across categories of one taxonomy, so the weight is
     * a share rather than a flag. Left out, it claims everything the taxonomy's
     * other categories have not: that is what dropping a security onto a category
     * means in the application, where the remainder waits in a virtual
     * "unassigned" node. Over-assigning is refused rather than clamped, since a
     * silently reduced share is a wrong allocation nobody sees.
     */
    public static Result assign(Client client, String taxonomyId, String categoryId, String vehicleUuid,
                    JsonObject body)
    {
        var taxonomy = find(client, taxonomyId);
        var category = findCategory(taxonomy, categoryId);
        var vehicle = findVehicle(client, vehicleUuid);

        var errors = new ArrayList<ApiException.FieldError>();
        rejectUnknownFields(body, Set.of("weight"), errors); //$NON-NLS-1$
        var requested = fraction(body, "weight", errors); //$NON-NLS-1$

        if (!errors.isEmpty())
            throw ApiException.validation(errors);

        var existing = assignmentOf(category, vehicle);
        var elsewhere = assignedWeight(taxonomy, vehicle) - (existing == null ? 0 : existing.getWeight());
        var available = Classification.ONE_HUNDRED_PERCENT - elsewhere;

        var weight = requested != null ? requested.intValue() : available;

        if (weight <= 0 || weight > available)
        {
            throw ApiException.validation(List.of(new ApiException.FieldError("weight", "invalid-range", //$NON-NLS-1$ //$NON-NLS-2$
                            available <= 0 ? vehicle.getName() + " is already fully assigned in this taxonomy" //$NON-NLS-1$
                                            : "weight must be above 0 and at most " //$NON-NLS-1$
                                                            + fractionOf(available) + ", what this taxonomy's " //$NON-NLS-1$
                                                            + "other categories leave for " + vehicle.getName()))); //$NON-NLS-1$
        }

        if (existing != null)
        {
            if (existing.getWeight() == weight)
                return new Result(EntityJson.toJson(taxonomy), taxonomy.getName(), null);

            existing.setWeight(weight);
        }
        else
        {
            var assignment = new Assignment(vehicle, weight);
            assignment.setRank(category.getAssignments().size());
            category.addAssignment(assignment);
        }

        client.markDirty();

        return new Result(EntityJson.toJson(taxonomy), taxonomy.getName(),
                        "assigned '" + vehicle.getName() + "' to category '" + category.getName() + "'"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * Removes the vehicle from that one category. It stays assigned wherever else
     * in the taxonomy it is - a split assignment is removed one category at a
     * time, which is also what makes the call idempotent per category.
     */
    public static Result unassign(Client client, String taxonomyId, String categoryId, String vehicleUuid)
    {
        var taxonomy = find(client, taxonomyId);
        var category = findCategory(taxonomy, categoryId);
        var vehicle = findVehicle(client, vehicleUuid);

        var assignment = assignmentOf(category, vehicle);
        if (assignment == null)
            throw ApiException.notFound();

        category.removeAssignment(assignment);
        client.markDirty();

        return new Result(EntityJson.toJson(taxonomy), taxonomy.getName(),
                        "removed '" + vehicle.getName() + "' from category '" + category.getName() + "'"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /* package */ static Taxonomy find(Client client, String taxonomyId)
    {
        return Entities.byUuid(client.getTaxonomies(), Taxonomy::getId, taxonomyId);
    }

    /**
     * An instrument or a cash account by its uuid. Investment accounts are
     * deliberately not searched: the model classifies the vehicles that hold
     * value, and a portfolio's value is its instruments'.
     */
    private static InvestmentVehicle findVehicle(Client client, String uuid)
    {
        for (Security security : client.getSecurities())
        {
            if (security.getUUID().equals(uuid))
                return security;
        }

        for (Account account : client.getAccounts())
        {
            if (account.getUUID().equals(uuid))
                return account;
        }

        throw ApiException.notFound();
    }

    private static Assignment assignmentOf(Classification category, InvestmentVehicle vehicle)
    {
        return category.getAssignments().stream() //
                        .filter(assignment -> vehicle.equals(assignment.getInvestmentVehicle())) //
                        .findFirst().orElse(null);
    }

    /** what the whole taxonomy already assigns of one vehicle */
    private static int assignedWeight(Taxonomy taxonomy, InvestmentVehicle vehicle)
    {
        var total = new int[1];
        taxonomy.foreach(new Taxonomy.Visitor()
        {
            @Override
            public void visit(Classification classification, Assignment assignment)
            {
                if (vehicle.equals(assignment.getInvestmentVehicle()))
                    total[0] += assignment.getWeight();
            }
        });
        return total[0];
    }

    private static String fractionOf(int weight)
    {
        return BigDecimal.valueOf(weight).divide(BigDecimal.valueOf(Classification.ONE_HUNDRED_PERCENT))
                        .stripTrailingZeros().toPlainString();
    }

    /**
     * The category by its id. The root is deliberately not addressable: it names
     * the taxonomy rather than a category, and is not reported as one either.
     */
    /* package */ static Classification findCategory(Taxonomy taxonomy, String categoryId)
    {
        var category = taxonomy.getClassificationById(categoryId);
        if (category == null)
            throw ApiException.notFound();
        return category;
    }

    /**
     * Renumbers a parent's children so that {@code child} sits at
     * {@code position}, or last when none was given. The application orders a
     * tree by rank, so this is what "third from the top" means in the file.
     */
    private static void placeAt(Classification parent, Classification child, Integer position)
    {
        var siblings = new ArrayList<>(parent.getChildren());
        siblings.sort(Comparator.comparingInt(Classification::getRank));
        siblings.remove(child);

        var index = position == null ? siblings.size() : Math.min(position, siblings.size());
        siblings.add(index, child);

        for (var i = 0; i < siblings.size(); i++)
            siblings.get(i).setRank(i);
    }

    /**
     * The parent named by the body: the root when the field is absent or null,
     * since the root is the taxonomy and a category directly under it is a
     * top-level one. Moving a category into its own subtree would detach that
     * subtree from the tree entirely, so it is refused rather than clamped.
     */
    private static Classification parentFor(Taxonomy taxonomy, Classification moved, JsonObject body,
                    List<ApiException.FieldError> errors)
    {
        var value = body.get("parent"); //$NON-NLS-1$
        if (value == null || value.isJsonNull())
            return taxonomy.getRoot();

        if (!isString(value))
        {
            errors.add(new ApiException.FieldError("parent", "invalid-type", //$NON-NLS-1$ //$NON-NLS-2$
                            "parent must be a category id or null")); //$NON-NLS-1$
            return taxonomy.getRoot();
        }

        var parent = taxonomy.getClassificationById(value.getAsString());
        if (parent == null)
        {
            errors.add(new ApiException.FieldError("parent", "unknown-reference", //$NON-NLS-1$ //$NON-NLS-2$
                            "no category " + value.getAsString() + " in this taxonomy")); //$NON-NLS-1$ //$NON-NLS-2$
            return taxonomy.getRoot();
        }

        if (moved != null && isSelfOrDescendant(moved, parent))
        {
            errors.add(new ApiException.FieldError("parent", "invalid-value", //$NON-NLS-1$ //$NON-NLS-2$
                            "a category cannot be moved into itself or one of its own children")); //$NON-NLS-1$
            return taxonomy.getRoot();
        }

        return parent;
    }

    private static boolean isSelfOrDescendant(Classification category, Classification candidate)
    {
        for (var node = candidate; node != null; node = node.getParent())
        {
            if (node == category)
                return true;
        }
        return false;
    }

    private static String color(JsonObject body, List<ApiException.FieldError> errors)
    {
        var value = body.get("color"); //$NON-NLS-1$
        if (value == null || value.isJsonNull())
            return null;

        if (!isString(value) || !COLOR.matcher(value.getAsString()).matches())
        {
            errors.add(new ApiException.FieldError("color", "invalid-value", //$NON-NLS-1$ //$NON-NLS-2$
                            "color must be a hex triplet such as #89afee")); //$NON-NLS-1$
            return null;
        }

        return value.getAsString();
    }

    /**
     * A target weight, given as a fraction of the parent category. Out of range
     * is refused rather than clamped: a client that meant 40 rather than 0.4
     * has to hear about it.
     */
    private static Integer weight(JsonObject body, List<ApiException.FieldError> errors)
    {
        return fraction(body, "weight", errors); //$NON-NLS-1$
    }

    /* package */ static Integer fraction(JsonObject body, String field, List<ApiException.FieldError> errors)
    {
        var value = body.get(field);
        if (value == null || value.isJsonNull())
            return null;

        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
        {
            errors.add(new ApiException.FieldError(field, "invalid-type", field + " must be a number")); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }

        var fraction = value.getAsBigDecimal();
        if (fraction.signum() < 0 || fraction.compareTo(BigDecimal.ONE) > 0)
        {
            errors.add(new ApiException.FieldError(field, "invalid-range", //$NON-NLS-1$
                            field + " must be a fraction between 0 and 1")); //$NON-NLS-1$
            return null;
        }

        return fraction.multiply(BigDecimal.valueOf(Classification.ONE_HUNDRED_PERCENT))
                        .setScale(0, RoundingMode.HALF_UP).intValueExact();
    }

    private static Integer position(JsonObject body, List<ApiException.FieldError> errors)
    {
        var value = body.get("position"); //$NON-NLS-1$
        if (value == null || value.isJsonNull())
            return null;

        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber() || value.getAsInt() < 0)
        {
            errors.add(new ApiException.FieldError("position", "invalid-value", //$NON-NLS-1$ //$NON-NLS-2$
                            "position must be a non-negative index among the siblings")); //$NON-NLS-1$
            return null;
        }

        return value.getAsInt();
    }

    private static void rejectUnknownFields(JsonObject body, Set<String> writable,
                    List<ApiException.FieldError> errors)
    {
        for (var field : body.keySet())
        {
            if (!writable.contains(field))
                errors.add(new ApiException.FieldError(field, "unknown-field", "field is not writable")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static String optionalText(JsonObject body, String field, List<ApiException.FieldError> errors)
    {
        var value = body.get(field);
        if (value == null || value.isJsonNull())
            return null;

        if (!isString(value))
        {
            errors.add(new ApiException.FieldError(field, "invalid-type", field + " must be a string or null")); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }

        return value.getAsString();
    }

    /* package */ static String requiredText(JsonObject body, String field,
                    List<ApiException.FieldError> errors)
    {
        var value = body.get(field);

        if (value == null || !isString(value) || value.getAsString().isBlank())
        {
            errors.add(new ApiException.FieldError(field, "required", field + " must be a non-empty string")); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }

        return value.getAsString().strip();
    }

    private static boolean isString(JsonElement value)
    {
        return value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
    }
}
