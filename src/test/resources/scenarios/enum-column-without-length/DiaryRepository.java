package fx;

import java.util.List;

public interface DiaryRepository {
    Diary save(Diary row);

    List<Diary> getByMood(Mood mood);
}
