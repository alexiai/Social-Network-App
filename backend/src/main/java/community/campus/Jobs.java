package community.campus;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class Jobs {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(Jobs.class);
    private final Store store;
    private final Inbox inbox;
    private final JavaMailSender mail;
    private final String from;

    Jobs(Store store, Inbox inbox, JavaMailSender mail, @Value("${app.mail-from}") String from) {
        this.store = store;
        this.inbox = inbox;
        this.mail = mail;
        this.from = from;
    }

    @Scheduled(fixedDelay=5000)
    @Transactional
    public void sendMail() {
        for (var row : store.rows("""
            select * from mail_queue where available_at<=now() and attempts<8
            order by available_at,id limit 3 for update skip locked
            """)) {
            var message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo((String) row.get("recipient"));
            message.setSubject((String) row.get("subject"));
            message.setText((String) row.get("body"));
            try {
                mail.send(message);
                store.write("delete from mail_queue where id=?", row.get("id"));
            } catch (MailException error) {
                LOG.warn("Mail job {} failed on attempt {}", row.get("id"), (Integer) row.get("attempts") + 1);
                store.write("update mail_queue set attempts=attempts+1,available_at=now()+interval '5 minutes' where id=?", row.get("id"));
            }
        }
    }

    @Scheduled(fixedDelay=60000)
    @Transactional
    public void reminders() {
        for (var row : store.rows("""
            update rsvps r set reminded=true from events e where r.event_id=e.id and not r.reminded
              and e.starts_at between now() and now()+interval '24 hours'
            returning r.username,r.event_id,e.organizer
            """)) {
            inbox.notify((String) row.get("username"), "EVENT_REMINDER", null, (java.util.UUID) row.get("event_id"));
        }
        store.write("delete from account_tokens where expires_at<now()");
        store.write("delete from mail_queue where available_at<now()-interval '1 day'");
    }
}
