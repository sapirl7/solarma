//! Instruction handlers

pub mod ack_awake;
pub mod claim;
pub mod create_alarm;
pub mod emergency_refund;
pub mod initialize;
pub mod slash;
pub mod snooze;
pub mod sweep_acknowledged;

// Re-export Accounts structs and Anchor-generated types for the #[program] macro.
// Handler functions have unique names (process_*) so no glob collision occurs.
pub use ack_awake::*;
pub use claim::*;
pub use create_alarm::*;
pub use emergency_refund::*;
pub use initialize::*;
pub use slash::*;
pub use snooze::*;
pub use sweep_acknowledged::*;
