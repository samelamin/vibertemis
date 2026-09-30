//go:build windows

// Windows syscalls not in golang.org/x/sys/windows.
package bridge

import (
	"fmt"
	"syscall"
	"unsafe"

	"golang.org/x/sys/windows"
)

// advapi32.dll procedures not yet in x/sys/windows.
//
//nolint:unused // Imported via syscalls.
var (
	modAdvapi32 = windows.NewLazySystemDLL("advapi32.dll")

	procImpersonateNamedPipeClient = modAdvapi32.NewProc("ImpersonateNamedPipeClient")
	procRevertToSelf               = modAdvapi32.NewProc("RevertToSelf")
)

// impersonateNamedPipeClient wraps advapi32!ImpersonateNamedPipeClient.
// BOOL ImpersonateNamedPipeClient(HANDLE hNamedPipe);
func impersonateNamedPipeClient(pipe windows.Handle) error {
	r1, _, e1 := syscall.SyscallN(procImpersonateNamedPipeClient.Addr(), uintptr(pipe))
	if r1 == 0 {
		if e1 != 0 {
			return fmt.Errorf("advapi32!ImpersonateNamedPipeClient: %v", e1)
		}
		return fmt.Errorf("advapi32!ImpersonateNamedPipeClient failed")
	}
	return nil
}

// revertToSelf wraps advapi32!RevertToSelf.
// BOOL RevertToSelf(void);
func revertToSelf() error {
	r1, _, e1 := syscall.SyscallN(procRevertToSelf.Addr())
	if r1 == 0 {
		if e1 != 0 {
			return fmt.Errorf("advapi32!RevertToSelf: %v", e1)
		}
		return fmt.Errorf("advapi32!RevertToSelf failed")
	}
	return nil
}

// avoid "unused import" complaints when only used through
// unsafe.Pointer arithmetic.
var _ = unsafe.Pointer(nil)
