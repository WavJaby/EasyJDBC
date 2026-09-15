package fx;

import java.util.List;

public interface MemberRepository {
    Member save(Member row);

    Member getByHandle(String handle);

    List<Member> getAll();
}
