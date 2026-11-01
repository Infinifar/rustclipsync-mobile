//! Calls every core HTTP operation against the disposable loopback TLS route fixture.
use rustclipsync_core::{
    client::Client,
    state::{Ack, LocalCopy, Message, Outbox, Persisted, State},
};
use std::{env, error::Error, io};

// Published synthetic identifiers shared with scripts/test-core-api.py.
const EPOCH: &str = "10000000-0000-4000-8000-000000000001";
const DEVICE: &str = "20000000-0000-4000-8000-000000000001";
const MESSAGE: &str = "30000000-0000-4000-8000-000000000001";
const BODY: &str = "synthetic route fixture";

fn main() -> Result<(), Box<dyn Error>> {
    let base = env::args().nth(1).ok_or_else(|| {
        io::Error::new(
            io::ErrorKind::InvalidInput,
            "expected loopback TLS fixture URL",
        )
    })?;
    // Never allow the self-signed-certificate exception to target an external service.
    let port = base
        .strip_prefix("https://127.0.0.1:")
        .and_then(|tail| tail.split('/').next())
        .and_then(|port| port.parse::<u16>().ok());
    if port.is_none_or(|port| port == 0)
        || env::var("RCS_MOBILE_DISPOSABLE_TEST").as_deref() != Ok("1")
    {
        return Err(
            io::Error::new(io::ErrorKind::InvalidInput, "isolated fixture required").into(),
        );
    }
    let client = Client::new(&base, "synthetic".into(), "synthetic".into(), true)?;
    let session = Persisted {
        server: base.clone(),
        token: "11".repeat(32),
        paired: true,
        state: State::new(EPOCH.into(), DEVICE.into(), 0),
        inbox: None,
    };
    let registration = client.register(&session)?;
    assert_eq!(registration.epoch, EPOCH, "registration epoch");
    assert_eq!(client.state(&session)?.revision, 1, "state revision");
    assert!(
        client.changes(&session)?.is_none(),
        "empty changes response"
    );
    let message = Message {
        id: MESSAGE.into(),
        revision: 1,
        source_device_id: DEVICE.into(),
        sensitive: false,
    };
    assert_eq!(
        client.content(&session, &message)?,
        BODY,
        "content response"
    );
    let outbox = Outbox {
        copy: LocalCopy {
            id: MESSAGE.into(),
            text: BODY.into(),
            sensitive: false,
            created_at: 0,
        },
        base_revision: 0,
        attempted: true,
    };
    assert_eq!(client.send(&session, &outbox)?, 1, "send revision");
    assert_eq!(client.cancel(&session, &outbox)?, None, "cancel response");
    client.acknowledge(
        &session,
        &Ack {
            id: MESSAGE.into(),
            revision: 1,
            result: "skipped".into(),
        },
    )?;
    println!("All seven core HTTP operations completed against the synthetic fixture");
    Ok(())
}
