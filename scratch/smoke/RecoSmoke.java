import com.university.service.RecommendationService;
import com.university.service.RecommendationService.Recommendation;
import com.university.service.RecommendationService.RecommendationResult;

public class RecoSmoke {
    public static void main(String[] args) {
        RecommendationService service = new RecommendationService();
        int[] ids = new int[args.length];
        for (int i = 0; i < args.length; i++) ids[i] = Integer.parseInt(args[i]);

        for (int studentId : ids) {
            System.out.println("=====================================================");
            System.out.println("STUDENT " + studentId);
            try {
                RecommendationResult r = service.recommend(studentId);
                if (r.isBlocked()) {
                    System.out.println("BLOCKED: " + r.blockedReason);
                    continue;
                }
                System.out.println("program=" + r.programName + " currentSemester=" + r.currentSemester
                        + " gpa=" + r.cumulativeGpa + " currentCredits=" + r.currentCredits
                        + "/" + r.creditLimit + " completedCredits=" + r.completedCredits);
                System.out.println("sectionsConsidered=" + r.sectionsConsidered
                        + " sectionsSurviving=" + r.sectionsSurviving);
                System.out.println("--- Recommendations (" + r.recommendations.size() + ") ---");
                for (Recommendation rec : r.recommendations) {
                    System.out.println("  #" + rec.rank + " tier=" + rec.planPriorityTier
                            + " " + rec.courseCode + " score=" + rec.finalScore
                            + " credits=" + rec.credits + " section=" + rec.sectionNumber);
                }
                System.out.println("--- Warnings (" + r.warnings.size() + ") ---");
                for (String w : r.warnings) {
                    System.out.println("  " + w);
                }
            } catch (RuntimeException e) {
                System.out.println("ERROR: " + e);
                e.printStackTrace();
            }
        }
    }
}
