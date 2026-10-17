package fx;

public interface PersonRepository {
    Misnamed getMisnamedById(long id);

    Pinned getPinnedById(long id);
}
